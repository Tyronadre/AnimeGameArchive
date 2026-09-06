use anyhow::anyhow;
use async_trait::async_trait;
use pcap::{Active, Capture, ConnectionStatus, Device, Error as PcapError};
use tokio::sync::mpsc::{self, UnboundedReceiver, UnboundedSender};

use crate::capture::{CaptureBackend, CaptureError, PORT_RANGE, Result};

pub struct PcapBackend {
    packet_rx: UnboundedReceiver<Result<Vec<u8>>>,
}

impl PcapBackend {
    pub fn new() -> Result<Self> {
        let devices = Device::list().map_err(|error| CaptureError::Capture {
            has_captured: false,
            error: error.into(),
        })?;

        tracing::info!(devices = devices.len(), "Found available capture devices");
        let filter = format!("udp and portrange {}-{}", PORT_RANGE.0, PORT_RANGE.1);
        let mut captures = Vec::new();

        for device in devices {
            if device.flags.connection_status != ConnectionStatus::Connected {
                continue;
            }
            match Self::setup_device_capture(device, &filter) {
                Ok(capture) => captures.push(capture),
                Err(error) => tracing::debug!(%error, "Capture device could not be opened"),
            }
        }

        if captures.is_empty() {
            return Err(CaptureError::Capture {
                has_captured: false,
                error: anyhow!("No connected capture device could be opened"),
            });
        }

        let (packet_tx, packet_rx) = mpsc::unbounded_channel();
        for (device_name, capture) in captures {
            let packet_tx = packet_tx.clone();
            std::thread::spawn(move || Self::packet_loop(capture, packet_tx, device_name));
        }
        drop(packet_tx);

        Ok(Self { packet_rx })
    }

    fn setup_device_capture(device: Device, filter: &str) -> Result<(String, Capture<Active>)> {
        let device_name = format!(
            "{} ({})",
            device.name,
            device.desc.as_deref().unwrap_or("no description")
        );
        let mut capture = Capture::from_device(device)
            .map_err(|error| CaptureError::Capture {
                has_captured: false,
                error: error.into(),
            })?
            .immediate_mode(true)
            .timeout(1_000)
            .open()
            .map_err(|error| CaptureError::Capture {
                has_captured: false,
                error: error.into(),
            })?;
        capture
            .filter(filter, true)
            .map_err(|error| CaptureError::Filter(error.into()))?;
        Ok((device_name, capture))
    }

    fn packet_loop(
        mut capture: Capture<Active>,
        packet_tx: UnboundedSender<Result<Vec<u8>>>,
        device_name: String,
    ) {
        let mut has_captured = false;
        loop {
            match capture.next_packet() {
                Ok(packet) => {
                    has_captured = true;
                    if packet_tx.send(Ok(packet.data.to_vec())).is_err() {
                        break;
                    }
                }
                Err(PcapError::TimeoutExpired) => continue,
                Err(error) => {
                    tracing::info!(%device_name, %error, has_captured, "Capture device stopped");
                    let _ = packet_tx.send(Err(CaptureError::Capture {
                        has_captured,
                        error: error.into(),
                    }));
                    break;
                }
            }
        }
    }
}

#[async_trait]
impl CaptureBackend for PcapBackend {
    async fn next_packet(&mut self) -> Result<Vec<u8>> {
        match self.packet_rx.recv().await {
            Some(packet) => packet,
            None => Err(CaptureError::CaptureClosed),
        }
    }
}
