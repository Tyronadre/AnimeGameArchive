// The code from https://github.com/IceDynamix/reliquary-archiver
//
// MIT License
//
// Copyright (c) 2024 IceDynamix
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

#[cfg(windows)]
pub fn ensure_admin() {
    if unsafe { windows::Win32::UI::Shell::IsUserAnAdmin().into() } {
        tracing::info!("Running with admin privileges");
        return;
    }

    tracing::info!("Escalating to admin privileges");

    use std::env;
    use std::os::windows::ffi::OsStrExt;

    use windows::Win32::System::Console::GetConsoleWindow;
    use windows::Win32::UI::Shell::{
        SEE_MASK_NO_CONSOLE, SEE_MASK_NOCLOSEPROCESS, SHELLEXECUTEINFOW, ShellExecuteExW,
    };
    use windows::Win32::UI::WindowsAndMessaging::{GW_OWNER, GetWindow, SW_SHOWNORMAL};
    use windows::core::{PCWSTR, w};

    let args_str = env::args().skip(1).collect::<Vec<_>>().join(" ");

    let exe_path = env::current_exe()
        .expect("Failed to get current exe")
        .as_os_str()
        .encode_wide()
        .chain(Some(0))
        .collect::<Vec<_>>();
    let args = args_str.encode_utf16().chain(Some(0)).collect::<Vec<_>>();

    unsafe {
        let mut options = SHELLEXECUTEINFOW {
            cbSize: size_of::<SHELLEXECUTEINFOW>() as u32,
            fMask: SEE_MASK_NOCLOSEPROCESS | SEE_MASK_NO_CONSOLE,
            hwnd: GetWindow(GetConsoleWindow(), GW_OWNER).unwrap_or(GetConsoleWindow()),
            lpVerb: w!("runas"),
            lpFile: PCWSTR(exe_path.as_ptr()),
            lpParameters: PCWSTR(args.as_ptr()),
            lpDirectory: PCWSTR::null(),
            nShow: SW_SHOWNORMAL.0,
            lpIDList: std::ptr::null_mut(),
            lpClass: PCWSTR::null(),
            dwHotKey: 0,
            ..Default::default()
        };

        if let Err(e) = ShellExecuteExW(&mut options) {
            tracing::error!("unable to run self with admin privs: {e}");
        }
    };

    // Exit the current process since we launched a new elevated one
    std::process::exit(0);
}

#[cfg(unix)]
fn is_root() -> bool {
    nix::unistd::geteuid().is_root()
}

#[cfg(target_os = "linux")]
fn has_cap_net_raw() -> bool {
    caps::has_cap(None, caps::CapSet::Effective, caps::Capability::CAP_NET_RAW)
        .is_ok_and(|has_net_raw| has_net_raw)
}

#[cfg(all(unix, not(target_os = "linux")))]
fn has_cap_net_raw() -> bool {
    false
}

#[cfg(unix)]
pub fn ensure_admin() {
    if is_root() || has_cap_net_raw() {
        tracing::info!("Packet capture privileges are available");
        return;
    }

    #[cfg(target_os = "macos")]
    if std::fs::File::open("/dev/bpf0").is_ok() {
        tracing::info!("Packet capture device is readable");
        return;
    }

    let executable = std::env::current_exe()
        .map(|path| path.to_string_lossy().into_owned())
        .unwrap_or_else(|_| "./genshin-irminsul-helper".to_owned());
    #[cfg(target_os = "linux")]
    let instructions = format!(
        "grant capture permission with `sudo setcap cap_net_raw=ep '{executable}'`, or run as root"
    );
    #[cfg(target_os = "macos")]
    let instructions = "grant read permission for /dev/bpf* or run as root".to_owned();
    #[cfg(all(unix, not(any(target_os = "linux", target_os = "macos"))))]
    let instructions = "run as root".to_owned();

    tracing::error!(%instructions, "Packet capture permission is missing");
    eprintln!("Irminsul helper cannot capture packets: {instructions}");
    std::process::exit(1);
}
