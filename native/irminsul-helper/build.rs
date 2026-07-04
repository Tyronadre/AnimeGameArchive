use std::fs::File;
use std::io;
use std::path::Path;

use flate2::Compression;
use flate2::write::GzEncoder;

#[tokio::main]
async fn main() -> io::Result<()> {
    let out_dir = std::env::var_os("OUT_DIR").expect("OUT_DIR must be set");
    let cache_path = Path::new(&out_dir).join("game_data.json");
    let output_path = Path::new(&out_dir).join("game_data.gz");
    let mut database = anime_game_data::AnimeGameData::new_with_cache(&cache_path);

    if !output_path.is_file() || database.needs_update().await.unwrap_or(true) {
        database.update().await.unwrap();
        let file = File::create(output_path)?;
        let writer = GzEncoder::new(file, Compression::best());
        database.save_to_writer(writer).unwrap();
    }

    println!("cargo:rerun-if-changed=build.rs");
    Ok(())
}
