#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::collections::HashMap;
use std::time::Duration;

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
use crate::player_data::{ExportSettings, PlayerData};

mod admin;
mod capture;
mod good;
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
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::new("info"))
        .with_ansi(false)
        .init();

    let args = Args::parse();
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

async fn run_capture(client: &Client, args: &Args) -> Result<()> {
    let database = load_database()?;
    let mut player_data = PlayerData::new(database);
    let keys = load_keys()?;
    let mut sniffer = GameSniffer::new().set_initial_keys(keys);
    let mut capture =
        PacketCapture::new().map_err(|error| anyhow!("packet capture could not start: {error}"))?;

    send_status(
        client,
        args,
        "waiting_for_game",
        "Capture is running. Start Genshin Impact and enter the game.",
    )
    .await?;

    let mut control_poll = interval(Duration::from_secs(1));
    control_poll.set_missed_tick_behavior(MissedTickBehavior::Skip);
    let mut control_failures = 0;
    let mut captured_items = false;
    let mut captured_characters = false;

    loop {
        tokio::select! {
            _ = control_poll.tick() => {
                match cancellation_requested(client, args).await {
                    Ok(true) => {
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
            packet = capture.next_packet() => {
                let packet = packet
                    .map_err(|error| anyhow!("packet capture failed: {error}"))?;
                let Some(GamePacket::Commands(commands)) = sniffer.receive_packet(packet) else {
                    continue;
                };

                for command in commands {
                    if let Some(items) = matches_item_packet(&command) {
                        player_data.process_items(&items);
                        if !captured_items {
                            captured_items = true;
                            send_status(
                                client,
                                args,
                                "items_captured",
                                &format!("Captured {} inventory entries. Waiting for characters…", items.len()),
                            )
                            .await?;
                        }
                    } else if let Some(characters) = matches_avatar_packet(&command) {
                        player_data.process_characters(&characters);
                        if !captured_characters {
                            captured_characters = true;
                            send_status(
                                client,
                                args,
                                "characters_captured",
                                &format!("Captured {} characters. Waiting for inventory…", characters.len()),
                            )
                            .await?;
                        }
                    }
                }
            }
        }

        if captured_items && captured_characters {
            send_status(
                client,
                args,
                "uploading",
                "Complete game snapshot captured. Importing it now…",
            )
            .await?;
            let snapshot = player_data.export_genshin_optimizer(&full_export_settings())?;
            post_snapshot(client, args, snapshot).await?;
            return Ok(());
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
