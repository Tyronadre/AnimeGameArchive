use auto_artifactarium::{GameCommand, matches_avatar_packet, matches_item_packet};

fn command(id: u16, body: Vec<u8>) -> GameCommand {
    GameCommand {
        command_id: id,
        header_len: 0,
        data_len: body.len() as u32,
        proto_data: body,
    }
}

#[test]
fn decodes_72_inventory_command_and_outer_field() {
    // Literal wire fixture: outer field 2, Item(id=1, guid=2, Material(count=3)).
    // Do not generate this with the schema being tested.
    let body = [0x12, 8, 0x08, 1, 0x10, 2, 0x2a, 2, 0x08, 3].repeat(10);
    let items = matches_item_packet(&command(20892, body.clone())).unwrap();
    assert_eq!(items.len(), 10);
    assert_eq!(items[0].material().count, 3);
    assert!(matches_item_packet(&command(22160, body)).is_none());
}

#[test]
fn decodes_72_character_command_and_outer_field() {
    // Outer field 6, AvatarInfo(avatar_id=1, guid=2).
    let body = vec![0x32, 4, 0x08, 1, 0x10, 2];
    let avatars = matches_avatar_packet(&command(20143, body.clone())).unwrap();
    assert_eq!(avatars.len(), 1);
    assert_eq!(avatars[0].guid, 2);
    assert!(matches_avatar_packet(&command(27799, body)).is_none());
}
