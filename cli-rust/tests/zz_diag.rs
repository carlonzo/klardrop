//! TEMPORARY diagnostic — not a real test, delete before merging.
//!
//! Answers, on whatever platform it runs on, what the pty master actually
//! delivers: whether the client's colour probe ran, where the BEL bytes came
//! from, how many newlines the client's own output carries, and how many rows
//! the reconstructed screen ends up with.

mod support;

use support::{Fixture, Tui};

fn visible(bytes: &[u8]) -> String {
    bytes
        .iter()
        .map(|b| match *b {
            0x1b => "\\e".to_string(),
            0x07 => "\\a".to_string(),
            0x00 => "\\0".to_string(),
            b'\n' => "\\n\n".to_string(),
            b'\r' => "\\r".to_string(),
            0x20..=0x7e => (*b as char).to_string(),
            other => format!("\\x{other:02x}"),
        })
        .collect()
}

fn count(raw: &[u8], needle: &[u8]) -> usize {
    raw.windows(needle.len()).filter(|w| *w == needle).count()
}

#[test]
fn diag_what_the_pty_master_delivers() {
    let fixture = Fixture::start("diag", &[]);
    let tui = Tui::launch(&fixture);
    tui.wait_for("Fixture Laptop");

    let raw = tui.raw();
    let screen = tui.screen();
    let text = tui.text();

    println!("DIAG platform            = {}", std::env::consts::OS);
    println!("DIAG raw bytes           = {}", raw.len());
    println!("DIAG newlines in raw     = {}", count(&raw, b"\n"));
    println!("DIAG CRs in raw          = {}", count(&raw, b"\r"));
    println!("DIAG BEL count           = {}", raw.iter().filter(|b| **b == 0x07).count());
    println!(
        "DIAG BEL offsets         = {:?}",
        raw.iter()
            .enumerate()
            .filter(|(_, b)| **b == 0x07)
            .map(|(i, _)| i)
            .take(10)
            .collect::<Vec<_>>()
    );
    println!("DIAG ESC bytes           = {}", raw.iter().filter(|b| **b == 0x1b).count());
    println!("DIAG CSI '[' count       = {}", count(&raw, b"\x1b["));
    println!("DIAG CUP 'H' count       = {}", count(&raw, b"\x1b[H") + count(&raw, b"f"));
    println!("DIAG CUU 'A' count       = {}", count(&raw, b"\x1b[A"));
    println!("DIAG CUD 'B' count       = {}", count(&raw, b"\x1b[B"));
    println!("DIAG CUF 'C' count       = {}", count(&raw, b"\x1b[C"));
    println!("DIAG colour probe sent   = {}", count(&raw, b"\x1b]10;?"));
    println!("DIAG screen().len()      = {}", screen.len());
    println!("DIAG text().lines()      = {}", text.lines().count());
    println!(
        "DIAG screen line lengths = {:?}",
        screen.iter().map(|r| r.len()).collect::<Vec<_>>()
    );

    let head = raw.len().min(1200);
    println!("DIAG raw head:\n{}", visible(&raw[..head]));
    let tail_start = raw.len().saturating_sub(800);
    println!("DIAG raw tail:\n{}", visible(&raw[tail_start..]));
}
