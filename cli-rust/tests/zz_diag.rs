//! TEMPORARY diagnostic — not a real test, delete before merging.
//!
//! Question 2: on Windows the pty master carries ConPTY's RENDERING, not the
//! client's bytes. Is the row structure recoverable by splitting that stream on
//! CRLF and stripping ANSI, instead of feeding it through a second vt100 pass?

mod support;

use support::{Fixture, Tui};

/// Drops CSI/OSC sequences the way a terminal would, leaving printable bytes.
fn strip_ansi(bytes: &[u8]) -> String {
    let mut out: Vec<u8> = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] != 0x1b {
            if bytes[i] >= 0x20 || matches!(bytes[i], b'\n' | b'\r' | b'\t') {
                out.push(bytes[i]);
            }
            i += 1;
            continue;
        }
        i += 1; // the ESC itself
        if i < bytes.len() && bytes[i] == b']' {
            i += 1; // OSC introducer
            while i < bytes.len() && bytes[i] != 0x07 {
                if bytes[i] == 0x1b && i + 1 < bytes.len() && bytes[i + 1] == b'\\' {
                    i += 1;
                    break;
                }
                i += 1;
            }
            i += 1; // BEL or ST
            continue;
        }
        if i < bytes.len() && bytes[i] == b'[' {
            i += 1; // CSI introducer
            while i < bytes.len() && (0x20..0x30).contains(&bytes[i]) {
                i += 1;
            }
            while i < bytes.len() && (0x30..0x40).contains(&bytes[i]) {
                i += 1;
            }
            if i < bytes.len() {
                i += 1; // the final byte
            }
            continue;
        }
        // Any other two-character escape: drop the next byte too.
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

#[test]
fn diag_can_windows_rows_be_recovered() {
    let fixture = Fixture::start("diag2", &[]);
    let tui = Tui::launch(&fixture);
    tui.wait_for("Fixture Laptop");

    let raw = tui.raw();
    let plain = strip_ansi(&raw);
    let lines: Vec<&str> = plain.lines().collect();

    println!("DIAG2 platform        = {}", std::env::consts::OS);
    println!("DIAG2 raw bytes       = {}", raw.len());
    println!("DIAG2 plain bytes     = {}", plain.len());
    println!("DIAG2 lines after ANSI-strip = {}", lines.len());
    println!("DIAG2 line lengths    = {:?}", lines.iter().map(|l| l.len()).take(12).collect::<Vec<_>>());
    for (n, line) in lines.iter().enumerate() {
        println!("DIAG2 [{n}] len={} {:?}", line.len(), line.chars().take(100).collect::<String>());
    }
    println!(
        "DIAG2 selected rows   = {}",
        lines.iter().filter(|l| l.trim_start_matches(['│', '|']).starts_with('>')).count()
    );
}
