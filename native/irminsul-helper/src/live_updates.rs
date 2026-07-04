use std::collections::HashMap;

use auto_artifactarium::{GameCommand, r#gen::protos::Item};
use protobuf::Message;

/// Command IDs and outer protobuf field numbers can change between game
/// versions. Complete Item records are distinctive enough to recognize safely:
/// they contain an item ID, a GUID, and material/equipment/furniture details.
pub fn parse_item_changes(command: &GameCommand) -> Option<Vec<Item>> {
    let mut fields: HashMap<u64, (usize, Vec<Item>)> = HashMap::new();
    for (field_number, value) in parse_wire_fields(&command.proto_data)? {
        let WireValue::LengthDelimited(bytes) = value else {
            continue;
        };
        let entry = fields.entry(field_number).or_default();
        entry.0 += 1;
        if let Ok(item) = Item::parse_from_bytes(bytes)
            && is_complete_store_item(&item)
        {
            entry.1.push(item);
        }
    }

    fields
        .into_values()
        .filter(|(payload_count, items)| !items.is_empty() && *payload_count == items.len())
        .max_by_key(|(_, items)| items.len())
        .map(|(_, items)| items)
}

/// Deletion messages contain either a packed or repeated list of GUIDs. The
/// caller additionally verifies that every candidate GUID exists in the
/// captured inventory before applying it.
pub fn parse_item_deletions(command: &GameCommand) -> Option<Vec<u64>> {
    let mut candidates = Vec::new();
    let mut repeated_varints: HashMap<u64, Vec<u64>> = HashMap::new();
    for (field_number, value) in parse_wire_fields(&command.proto_data)? {
        match value {
            WireValue::Varint(value) => {
                repeated_varints
                    .entry(field_number)
                    .or_default()
                    .push(value);
            }
            WireValue::LengthDelimited(bytes) => {
                if let Some(values) = parse_packed_guids(bytes) {
                    candidates.push(values);
                }
            }
        }
    }
    candidates.extend(
        repeated_varints
            .into_values()
            .filter(|values| values.iter().all(is_probable_guid)),
    );
    candidates.into_iter().max_by_key(Vec::len)
}

fn is_complete_store_item(item: &Item) -> bool {
    item.item_id != 0
        && item.guid != 0
        && (item.has_material() || item.has_equip() || item.has_furniture())
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

fn parse_wire_fields(bytes: &[u8]) -> Option<Vec<(u64, WireValue<'_>)>> {
    let mut cursor = 0;
    let mut fields = Vec::new();
    while cursor < bytes.len() {
        let key = read_varint(bytes, &mut cursor)?;
        let field_number = key >> 3;
        if field_number == 0 {
            return None;
        }
        let value = match key & 0x07 {
            0 => WireValue::Varint(read_varint(bytes, &mut cursor)?),
            1 => {
                cursor = cursor.checked_add(8)?;
                if cursor > bytes.len() {
                    return None;
                }
                continue;
            }
            2 => {
                let length = usize::try_from(read_varint(bytes, &mut cursor)?).ok()?;
                let end = cursor.checked_add(length)?;
                let value = WireValue::LengthDelimited(bytes.get(cursor..end)?);
                cursor = end;
                value
            }
            5 => {
                cursor = cursor.checked_add(4)?;
                if cursor > bytes.len() {
                    return None;
                }
                continue;
            }
            _ => return None,
        };
        fields.push((field_number, value));
    }
    Some(fields)
}

fn parse_packed_guids(bytes: &[u8]) -> Option<Vec<u64>> {
    let mut cursor = 0;
    let mut values = Vec::new();
    while cursor < bytes.len() {
        values.push(read_varint(bytes, &mut cursor)?);
    }
    (!values.is_empty() && values.iter().all(is_probable_guid)).then_some(values)
}

fn is_probable_guid(value: &u64) -> bool {
    *value > u32::MAX as u64
}

enum WireValue<'a> {
    Varint(u64),
    LengthDelimited(&'a [u8]),
}

#[cfg(test)]
mod tests {
    use auto_artifactarium::{
        GameCommand,
        r#gen::protos::{Item, Material},
    };
    use protobuf::Message;

    use super::{parse_item_changes, parse_item_deletions};

    const ITEM_GUID: u64 = 0x1234_5678_9abc_def0;

    #[test]
    fn recognizes_complete_store_item_changes_without_stable_field_or_command_ids() {
        let mut item = Item::new();
        item.item_id = 104003;
        item.guid = ITEM_GUID;
        let mut material = Material::new();
        material.count = 42;
        item.set_material(material);

        let item_bytes = item.write_to_bytes().unwrap();
        let mut bytes = vec![0xb8, 0x01, 0x01, 0x8a, 0x01, item_bytes.len() as u8];
        bytes.extend(item_bytes);
        let command = command(bytes);
        let items = parse_item_changes(&command).unwrap();

        assert_eq!(1, items.len());
        assert_eq!(42, items[0].material().count);
    }

    #[test]
    fn rejects_incomplete_item_shaped_messages() {
        let mut item = Item::new();
        item.item_id = 104003;
        item.guid = ITEM_GUID;
        item.set_material(Material::new());
        item.guid = 0;
        let item_bytes = item.write_to_bytes().unwrap();
        let mut bytes = vec![0x12, item_bytes.len() as u8];
        bytes.extend(item_bytes);

        assert!(parse_item_changes(&command(bytes)).is_none());
    }

    #[test]
    fn recognizes_packed_store_item_deletions() {
        let bytes = vec![
            0x88, 0x01, 0x01, 0x4a, 0x09, 0xf0, 0xbd, 0xf3, 0xd5, 0x89, 0xcf, 0x95, 0x9a, 0x12,
        ];

        assert_eq!(Some(vec![ITEM_GUID]), parse_item_deletions(&command(bytes)));
    }

    #[test]
    fn rejects_small_values_that_are_unlikely_to_be_item_guids() {
        let bytes = vec![0x08, 0x01, 0x12, 0x01, 0x2a];

        assert!(parse_item_deletions(&command(bytes)).is_none());
    }

    fn command(proto_data: Vec<u8>) -> GameCommand {
        GameCommand {
            command_id: 999,
            header_len: 0,
            data_len: proto_data.len() as u32,
            proto_data,
        }
    }
}
