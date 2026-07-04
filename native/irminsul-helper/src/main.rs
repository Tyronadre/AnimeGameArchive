#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::collections::HashMap;
use std::fs::{OpenOptions, create_dir_all};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use anime_game_data::AnimeGameData;
use anyhow::{Context, Result, anyhow};
use auto_artifactarium::{GamePacket, GameSniffer, matches_avatar_packet, matches_item_packet};
use base64::prelude::*;
use clap::Parser;
use flate2::read::GzDecoder;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use tokio::time::{MissedTickBehavior, interval};

use crate::capture::PacketCapture;
use crate::live_updates::{parse_item_changes, parse_item_deletions};
use crate::player_data::{ExportSettings, PlayerData};

mod admin;
mod capture;
mod good;
mod live_updates;
mod player_data;

const TOKEN_HEADER: &str = "X-Genshin-Desktop-Token";
const SESSION_HEADER: &str = "X-Genshin-Capture-Session";

#[derive(Parser, Debug)]
#[command(version, about = "Irminsul capture helper for Genshin Archive")]
struct Args {
    #[arg(long)]
    endpoint: String,
    #[arg(long)]
    token: String,
    #[arg(long)]
    session: String,
    #[arg(long, default_value_t = false)]
    no_admin: bool,

    #[arg(long)]
    log_file: Option<PathBuf>,
}

#[derive(Serialize)]
struct StatusEvent<'a> {
    state: &'a str,
    message: &'a str,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ControlResponse {
    cancel_requested: bool,
}

#[tokio::main]
async fn main() {
    let args = Args::parse();
    configure_logging(args.log_file.as_deref());
    tracing::info!("Capture helper process starting");
    if !args.no_admin {
        #[cfg(windows)]
        admin::ensure_admin();
    }

    let client = Client::builder()
        .connect_timeout(Duration::from_secs(3))
        .timeout(Duration::from_secs(15))
        .build()
        .expect("HTTP client could not be created");

    if let Err(error) = run_capture(&client, &args).await {
        tracing::error!("capture failed: {error:#}");
        let _ = send_status(&client, &args, "error", &format!("Capture failed: {error}")).await;
        std::process::exit(1);
    }
}

fn configure_logging(log_file: Option<&Path>) {
    let file = log_file.and_then(|path| {
        path.parent()
            .and_then(|parent| create_dir_all(parent).ok())?;
        OpenOptions::new().create(true).append(true).open(path).ok()
    });
    let filter = tracing_subscriber::EnvFilter::new("info");
    if let Some(file) = file {
        tracing_subscriber::fmt()
            .with_env_filter(filter)
            .with_ansi(false)
            .with_writer(file)
            .init();
    } else {
        tracing_subscriber::fmt()
            .with_env_filter(filter)
            .with_ansi(false)
            .init();
    }
}

async fn run_capture(client: &Client, args: &Args) -> Result<()> {
    let database = load_database()?;
    let mut player_data = PlayerData::new(database);
    let keys = load_keys()?;
    let mut sniffer = GameSniffer::new().set_initial_keys(keys);
    let mut capture =
        PacketCapture::new().map_err(|error| anyhow!("packet capture could not start: {error}"))?;
    tracing::info!("Packet capture started; waiting for Genshin Impact traffic");

    send_status(
        client,
        args,
        "waiting_for_game",
        "Capture is running. Start Genshin Impact and enter the game.",
    )
    .await?;

    let mut control_poll = interval(Duration::from_secs(1));
    control_poll.set_missed_tick_behavior(MissedTickBehavior::Skip);
    let mut sync_poll = interval(Duration::from_millis(250));
    sync_poll.set_missed_tick_behavior(MissedTickBehavior::Skip);
    let mut diagnostics_poll = interval(Duration::from_secs(10));
    diagnostics_poll.set_missed_tick_behavior(MissedTickBehavior::Skip);
    let mut control_failures = 0;
    let mut captured_items = false;
    let mut captured_characters = false;
    let mut initial_snapshot_uploaded = false;
    let mut pending_changes = 0;
    let mut last_change = None;
    let mut commands_since_report = 0_usize;
    let mut packets_since_report = 0_usize;
    let mut recognized_since_report = 0_usize;
    let mut command_ids_since_report: HashMap<u16, usize> = HashMap::new();

    loop {
        tokio::select! {
            _ = control_poll.tick() => {
                match cancellation_requested(client, args).await {
                    Ok(true) => {
                        tracing::info!("Stop requested by the desktop application");
                        send_status(client, args, "stopped", "Capture stopped.").await?;
                        return Ok(());
                    }
                    Ok(false) => control_failures = 0,
                    Err(error) => {
                        control_failures += 1;
                        tracing::warn!("desktop control check failed: {error}");
                        if control_failures >= 5 {
                            return Err(anyhow!(
                                "desktop application is no longer reachable"
                            ));
                        }
                    }
                }
            }
            _ = sync_poll.tick() => {
                if initial_snapshot_uploaded
                    && pending_changes > 0
                    && last_change.is_some_and(|changed: Instant| {
                        changed.elapsed() >= Duration::from_millis(750)
                    })
                {
                    tracing::info!(
                        changes = pending_changes,
                        "Debounce window complete; exporting live snapshot"
                    );
                    send_status(
                        client,
                        args,
                        "syncing",
                        &format!(
                            "Saving {pending_changes} live inventory change{}…",
                            if pending_changes == 1 { "" } else { "s" },
                        ),
                    )
                    .await?;
                    let snapshot =
                        player_data.export_genshin_optimizer(&full_export_settings())?;
                    post_snapshot(client, args, snapshot).await?;
                    tracing::info!(
                        changes = pending_changes,
                        "Live snapshot saved to the desktop application"
                    );
                    pending_changes = 0;
                    last_change = None;
                }
            }
            _ = diagnostics_poll.tick() => {
                if initial_snapshot_uploaded {
                    let mut command_counts = command_ids_since_report
                        .iter()
                        .map(|(id, count)| (*id, *count))
                        .collect::<Vec<_>>();
                    command_counts.sort_by_key(|(_, count)| std::cmp::Reverse(*count));
                    let top_ids = command_counts
                        .into_iter()
                        .take(8)
                        .map(|(id, count)| format!("{id}×{count}"))
                        .collect::<Vec<_>>()
                        .join(", ");
                    tracing::info!(
                        packets = packets_since_report,
                        commands = commands_since_report,
                        recognized_changes = recognized_since_report,
                        top_command_ids = %top_ids,
                        "Live capture heartbeat"
                    );
                    commands_since_report = 0;
                    packets_since_report = 0;
                    recognized_since_report = 0;
                    command_ids_since_report.clear();
                }
            }
            packet = capture.next_packet() => {
                let packet = packet
                    .map_err(|error| anyhow!("packet capture failed: {error}"))?;
                if initial_snapshot_uploaded {
                    packets_since_report += 1;
                }
                let Some(GamePacket::Commands(commands)) = sniffer.receive_packet(packet) else {
                    continue;
                };

                for command in commands {
                    if initial_snapshot_uploaded {
                        commands_since_report += 1;
                        *command_ids_since_report
                            .entry(command.command_id)
                            .or_default() += 1;
                    }
                    if let Some(items) = matches_item_packet(&command) {
                        let changed = player_data.process_items(&items);
                        if !captured_items {
                            captured_items = true;
                            tracing::info!(
                                inventory_entries = items.len(),
                                "Captured initial inventory"
                            );
                            send_status(
                                client,
                                args,
                                "items_captured",
                                &format!("Captured {} inventory entries. Waiting for characters…", items.len()),
                            )
                            .await?;
                        }
                        if initial_snapshot_uploaded && changed {
                            pending_changes += items.len().max(1);
                            recognized_since_report += items.len().max(1);
                            last_change = Some(Instant::now());
                            tracing::info!(
                                command_id = command.command_id,
                                changed_items = items.len(),
                                "Captured a complete live inventory refresh"
                            );
                        }
                    } else if let Some(characters) = matches_avatar_packet(&command) {
                        let changed = player_data.process_characters(&characters);
                        if !captured_characters {
                            captured_characters = true;
                            tracing::info!(
                                characters = characters.len(),
                                "Captured initial characters"
                            );
                            send_status(
                                client,
                                args,
                                "characters_captured",
                                &format!("Captured {} characters. Waiting for inventory…", characters.len()),
                            )
                            .await?;
                        }
                        if initial_snapshot_uploaded && changed {
                            pending_changes += characters.len().max(1);
                            last_change = Some(Instant::now());
                        }
                    } else if captured_items {
                        if let Some(items) = parse_item_changes(&command) {
                            let changed = player_data.process_item_changes(&items);
                            if changed > 0 {
                                pending_changes += changed;
                                recognized_since_report += changed;
                                last_change = Some(Instant::now());
                                tracing::info!(
                                    command_id = command.command_id,
                                    changed_items = changed,
                                    "Captured live item additions or updates"
                                );
                            }
                        } else if let Some(guids) = parse_item_deletions(&command)
                            && player_data.contains_all_item_guids(&guids)
                        {
                            let changed = player_data.process_item_deletions(&guids);
                            if changed > 0 {
                                pending_changes += changed;
                                recognized_since_report += changed;
                                last_change = Some(Instant::now());
                                tracing::info!(
                                    command_id = command.command_id,
                                    deleted_items = changed,
                                    "Captured live item deletions"
                                );
                            }
                        }
                    }
                }
            }
        }

        if captured_items && captured_characters && !initial_snapshot_uploaded {
            send_status(
                client,
                args,
                "uploading",
                "Complete game snapshot captured. Starting live sync…",
            )
            .await?;
            let snapshot = player_data.export_genshin_optimizer(&full_export_settings())?;
            post_snapshot(client, args, snapshot).await?;
            tracing::info!("Initial snapshot saved; continuous live capture is active");
            initial_snapshot_uploaded = true;
            pending_changes = 0;
            last_change = None;
        }
    }
}

fn load_database() -> Result<AnimeGameData> {
    static DATABASE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/game_data.gz"));
    let reader = GzDecoder::new(DATABASE);
    AnimeGameData::new_from_reader(reader).context("embedded game database is invalid")
}

fn load_keys() -> Result<HashMap<u16, Vec<u8>>> {
    let keys: HashMap<u16, String> = serde_json::from_slice(include_bytes!("../keys/gi.json"))?;
    keys.iter()
        .map(|(key, value)| -> Result<_> { Ok((*key, BASE64_STANDARD.decode(value)?)) })
        .collect()
}

fn full_export_settings() -> ExportSettings {
    ExportSettings {
        include_characters: true,
        include_artifacts: true,
        include_weapons: true,
        include_materials: true,
        fake_initialize_4th_line: false,
        min_character_level: 1,
        min_character_ascension: 0,
        min_character_constellation: 0,
        min_artifact_level: 0,
        min_artifact_rarity: 1,
        min_weapon_level: 1,
        min_weapon_refinement: 1,
        min_weapon_ascension: 0,
        min_weapon_rarity: 1,
    }
}

async fn send_status(client: &Client, args: &Args, state: &str, message: &str) -> Result<()> {
    client
        .post(endpoint(args, "status"))
        .header(TOKEN_HEADER, &args.token)
        .header(SESSION_HEADER, &args.session)
        .json(&StatusEvent { state, message })
        .send()
        .await?
        .error_for_status()
        .context("desktop application rejected the capture status")?;
    Ok(())
}

async fn cancellation_requested(client: &Client, args: &Args) -> Result<bool> {
    let response = client
        .get(endpoint(args, "control"))
        .header(TOKEN_HEADER, &args.token)
        .header(SESSION_HEADER, &args.session)
        .send()
        .await?
        .error_for_status()?
        .json::<ControlResponse>()
        .await?;
    Ok(response.cancel_requested)
}

async fn post_snapshot(client: &Client, args: &Args, snapshot: String) -> Result<()> {
    client
        .post(endpoint(args, "snapshot"))
        .header(TOKEN_HEADER, &args.token)
        .header(SESSION_HEADER, &args.session)
        .header(reqwest::header::CONTENT_TYPE, "application/json")
        .body(snapshot)
        .send()
        .await?
        .error_for_status()
        .context("desktop application rejected the GOOD snapshot")?;
    Ok(())
}

fn endpoint(args: &Args, path: &str) -> String {
    format!("{}/{}", args.endpoint.trim_end_matches('/'), path)
}
