use std::fs::{File, OpenOptions, create_dir_all};
use std::io::{BufWriter, Write};
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

use auto_artifactarium::GameCommand;
use serde::Serialize;

const FORMAT_VERSION: &str = "genshin-archive-packet-inspection-v1";
const MAX_FIELDS_PER_LEVEL: usize = 48;
const MAX_DEPTH: usize = 4;
const MAX_HEX_PREFIX_BYTES: usize = 96;
const MAX_TEXT_BYTES: usize = 240;
const MAX_PACKED_VALUES: usize = 48;

#[derive(Clone, Debug)]
pub struct CommandInspection {
    category: &'static str,
    summary: Option<String>,
}

impl CommandInspection {
    pub fn unknown() -> Self {
        Self {
            category: "unknown",
            summary: None,
        }
    }

    pub fn new(category: &'static str, summary: impl Into<String>) -> Self {
        Self {
            category,
            summary: Some(summary.into()),
        }
    }
}

pub struct PacketInspectionLogger {
    writer: BufWriter<File>,
    sequence: u64,
    bytes_written: u64,
    max_bytes: u64,
    limit_reached: bool,
}

impl PacketInspectionLogger {
    pub fn open(path: Option<&Path>, max_bytes: u64) -> Option<Self> {
        let path = path?;
        if max_bytes == 0 {
            tracing::info!("Packet inspection logging disabled because max bytes is 0");
            return None;
        }
        let file = match open_log_file(path) {
            Ok(file) => file,
            Err(error) => {
                tracing::warn!(
                    path = %path.display(),
                    "Packet inspection log could not be opened: {error}"
                );
                return None;
            }
        };
        let bytes_written = file.metadata().map(|metadata| metadata.len()).unwrap_or(0);
        let mut logger = Self {
            writer: BufWriter::new(file),
            sequence: 0,
            bytes_written,
            max_bytes,
            limit_reached: bytes_written >= max_bytes,
        };
        logger.write_event(&SessionStartEvent {
            event: "session_start",
            timestamp_ms: timestamp_ms(),
            format: FORMAT_VERSION,
            note: "Local decoded game-command inspection log. Treat as private account/game data.",
        });
        tracing::info!(
            path = %path.display(),
            max_bytes,
            "Packet inspection logging enabled"
        );
        Some(logger)
    }

    pub fn log_command(&mut self, command: &GameCommand, inspection: &CommandInspection) {
        self.sequence += 1;
        let inspected = inspect_message(&command.proto_data, 0);
        let (fields, parse_error) = match inspected {
            Ok(fields) => (fields, None),
            Err(error) => (Vec::new(), Some(error)),
        };
        let event = CommandEvent {
            event: "command",
            timestamp_ms: timestamp_ms(),
            sequence: self.sequence,
            command_id: command.command_id,
            command_id_hex: format!("0x{:04x}", command.command_id),
            header_len: command.header_len,
            data_len: command.data_len,
            proto_bytes: command.proto_data.len(),
            proto_hex_prefix: hex_prefix(&command.proto_data),
            category: inspection.category,
            summary: inspection.summary.as_deref(),
            parse_error,
            fields,
        };
        self.write_event(&event);
        if self.sequence % 20 == 0 {
            let _ = self.writer.flush();
        }
    }

    fn write_event<T: Serialize>(&mut self, event: &T) {
        if self.limit_reached {
            return;
        }
        let line = match serde_json::to_string(event) {
            Ok(line) => line,
            Err(error) => {
                tracing::warn!("Packet inspection event could not be serialized: {error}");
                return;
            }
        };
        let next_size = line.len() as u64 + 1;
        if self.bytes_written.saturating_add(next_size) > self.max_bytes {
            let limit = LimitEvent {
                event: "limit_reached",
                timestamp_ms: timestamp_ms(),
                max_bytes: self.max_bytes,
                note: "Packet inspection stopped for this capture to keep the local log bounded.",
            };
            if let Ok(limit_line) = serde_json::to_string(&limit) {
                let _ = writeln!(self.writer, "{limit_line}");
                let _ = self.writer.flush();
            }
            self.limit_reached = true;
            return;
        }
        if writeln!(self.writer, "{line}").is_ok() {
            self.bytes_written += next_size;
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SessionStartEvent<'a> {
    event: &'static str,
    timestamp_ms: u64,
    format: &'static str,
    note: &'a str,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct LimitEvent<'a> {
    event: &'static str,
    timestamp_ms: u64,
    max_bytes: u64,
    note: &'a str,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CommandEvent<'a> {
    event: &'static str,
    timestamp_ms: u64,
    sequence: u64,
    command_id: u16,
    command_id_hex: String,
    header_len: u16,
    data_len: u32,
    proto_bytes: usize,
    proto_hex_prefix: String,
    category: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    summary: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    parse_error: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    fields: Vec<ProtoField>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ProtoField {
    field: u64,
    wire_type: u8,
    kind: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    value: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    value_hex: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    length: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    bytes_hex_prefix: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    packed_varints: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    nested: Option<Vec<ProtoField>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    note: Option<String>,
}

fn open_log_file(path: &Path) -> std::io::Result<File> {
    if let Some(parent) = path.parent() {
        create_dir_all(parent)?;
    }
    OpenOptions::new().create(true).append(true).open(path)
}

fn timestamp_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .min(u128::from(u64::MAX)) as u64
}

fn inspect_message(bytes: &[u8], depth: usize) -> Result<Vec<ProtoField>, String> {
    let mut cursor = 0;
    let mut fields = Vec::new();
    while cursor < bytes.len() {
        if fields.len() >= MAX_FIELDS_PER_LEVEL {
            fields.push(ProtoField {
                field: 0,
                wire_type: 0,
                kind: "truncated",
                value: None,
                value_hex: None,
                length: None,
                text: None,
                bytes_hex_prefix: None,
                packed_varints: None,
                nested: None,
                note: Some(format!(
                    "Only the first {MAX_FIELDS_PER_LEVEL} fields at this level are logged."
                )),
            });
            return Ok(fields);
        }
        let key = read_varint(bytes, &mut cursor).ok_or("incomplete protobuf field key")?;
        let field_number = key >> 3;
        if field_number == 0 {
            return Err("invalid protobuf field number 0".to_string());
        }
        let wire_type = (key & 0x07) as u8;
        let field = match wire_type {
            0 => {
                let value = read_varint(bytes, &mut cursor)
                    .ok_or_else(|| format!("field {field_number}: incomplete varint"))?;
                ProtoField {
                    field: field_number,
                    wire_type,
                    kind: "varint",
                    value: Some(value.to_string()),
                    value_hex: Some(format!("0x{value:x}")),
                    length: None,
                    text: None,
                    bytes_hex_prefix: None,
                    packed_varints: None,
                    nested: None,
                    note: None,
                }
            }
            1 => {
                let end = cursor
                    .checked_add(8)
                    .filter(|end| *end <= bytes.len())
                    .ok_or_else(|| format!("field {field_number}: incomplete fixed64"))?;
                let raw: [u8; 8] = bytes[cursor..end].try_into().expect("length checked");
                cursor = end;
                let value = u64::from_le_bytes(raw);
                ProtoField {
                    field: field_number,
                    wire_type,
                    kind: "fixed64",
                    value: Some(value.to_string()),
                    value_hex: Some(format!("0x{value:x}")),
                    length: None,
                    text: None,
                    bytes_hex_prefix: None,
                    packed_varints: None,
                    nested: None,
                    note: None,
                }
            }
            2 => {
                let length = usize::try_from(
                    read_varint(bytes, &mut cursor)
                        .ok_or_else(|| format!("field {field_number}: incomplete length"))?,
                )
                .map_err(|_| format!("field {field_number}: length is too large"))?;
                let end = cursor
                    .checked_add(length)
                    .filter(|end| *end <= bytes.len())
                    .ok_or_else(|| format!("field {field_number}: length exceeds payload"))?;
                let value = &bytes[cursor..end];
                cursor = end;
                inspect_length_delimited(field_number, wire_type, value, depth)
            }
            5 => {
                let end = cursor
                    .checked_add(4)
                    .filter(|end| *end <= bytes.len())
                    .ok_or_else(|| format!("field {field_number}: incomplete fixed32"))?;
                let raw: [u8; 4] = bytes[cursor..end].try_into().expect("length checked");
                cursor = end;
                let value = u32::from_le_bytes(raw);
                ProtoField {
                    field: field_number,
                    wire_type,
                    kind: "fixed32",
                    value: Some(value.to_string()),
                    value_hex: Some(format!("0x{value:x}")),
                    length: None,
                    text: None,
                    bytes_hex_prefix: None,
                    packed_varints: None,
                    nested: None,
                    note: None,
                }
            }
            3 | 4 => {
                return Err(format!(
                    "field {field_number}: protobuf groups are not supported by this logger"
                ));
            }
            _ => {
                return Err(format!(
                    "field {field_number}: invalid wire type {wire_type}"
                ));
            }
        };
        fields.push(field);
    }
    Ok(fields)
}

fn inspect_length_delimited(
    field_number: u64,
    wire_type: u8,
    bytes: &[u8],
    depth: usize,
) -> ProtoField {
    let text = printable_text(bytes);
    let nested = (depth < MAX_DEPTH)
        .then(|| inspect_message(bytes, depth + 1).ok())
        .flatten()
        .filter(|fields| !fields.is_empty());
    let packed_varints = parse_packed_varints(bytes);
    ProtoField {
        field: field_number,
        wire_type,
        kind: "length_delimited",
        value: None,
        value_hex: None,
        length: Some(bytes.len()),
        text,
        bytes_hex_prefix: Some(hex_prefix(bytes)),
        packed_varints,
        nested,
        note: (depth >= MAX_DEPTH && !bytes.is_empty())
            .then(|| format!("Nested inspection stops after depth {MAX_DEPTH}.")),
    }
}

fn read_varint(bytes: &[u8], cursor: &mut usize) -> Option<u64> {
    let mut value = 0_u64;
    for shift in (0..=63).step_by(7) {
        let byte = *bytes.get(*cursor)?;
        *cursor += 1;
        value |= u64::from(byte & 0x7f) << shift;
        if byte & 0x80 == 0 {
            return Some(value);
        }
    }
    None
}

fn parse_packed_varints(bytes: &[u8]) -> Option<Vec<String>> {
    if bytes.is_empty() {
        return None;
    }
    let mut cursor = 0;
    let mut values = Vec::new();
    while cursor < bytes.len() {
        values.push(read_varint(bytes, &mut cursor)?);
    }
    if values.len() == 1 && values[0] < 128 {
        return None;
    }
    let mut encoded = values
        .into_iter()
        .take(MAX_PACKED_VALUES)
        .map(|value| value.to_string())
        .collect::<Vec<_>>();
    if cursor == bytes.len() && encoded.len() == MAX_PACKED_VALUES {
        encoded.push(format!("… only first {MAX_PACKED_VALUES} values logged"));
    }
    Some(encoded)
}

fn printable_text(bytes: &[u8]) -> Option<String> {
    if bytes.is_empty() || bytes.len() > MAX_TEXT_BYTES {
        return None;
    }
    let text = std::str::from_utf8(bytes).ok()?;
    let printable = text
        .chars()
        .all(|character| !character.is_control() || matches!(character, '\n' | '\r' | '\t'));
    printable.then(|| text.to_string())
}

fn hex_prefix(bytes: &[u8]) -> String {
    let mut output = bytes
        .iter()
        .take(MAX_HEX_PREFIX_BYTES)
        .map(|byte| format!("{byte:02x}"))
        .collect::<Vec<_>>()
        .join("");
    if bytes.len() > MAX_HEX_PREFIX_BYTES {
        output.push('…');
    }
    output
}

#[cfg(test)]
mod tests {
    use auto_artifactarium::GameCommand;

    use super::{CommandInspection, PacketInspectionLogger, inspect_message};

    #[test]
    fn inspects_nested_length_delimited_fields() {
        let bytes = vec![0x12, 0x04, 0x08, 0x2a, 0x10, 0x2b];
        let fields = inspect_message(&bytes, 0).unwrap();

        assert_eq!(1, fields.len());
        assert_eq!(2, fields[0].field);
        assert_eq!(Some(4), fields[0].length);
        assert_eq!(2, fields[0].nested.as_ref().unwrap().len());
    }

    #[test]
    fn disabled_logger_can_be_constructed_without_path() {
        assert!(PacketInspectionLogger::open(None, 1024).is_none());
    }

    #[test]
    fn command_inspection_accepts_human_summary() {
        let inspection = CommandInspection::new("live_item_update", "1 item changed");
        let command = GameCommand {
            command_id: 42,
            header_len: 0,
            data_len: 2,
            proto_data: vec![0x08, 0x01],
        };
        let logger = PacketInspectionLogger::open(None, 1024);
        assert!(logger.is_none());
        assert_eq!(42, command.command_id);
        assert_eq!("live_item_update", inspection.category);
    }
}
