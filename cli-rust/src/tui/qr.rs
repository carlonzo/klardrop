//! Drawing a QR code with terminal cells.
//!
//! The daemon hands `/qr-share` its URL and the module matrix for it: one
//! string per row, `1` for a dark module, `0` for a light one, and **no quiet
//! zone**. The quiet zone is the decoder's requirement, not the encoder's
//! output, so it is added here — a matrix drawn without it is not a QR code,
//! it is a picture of one.
//!
//! Two facts decide whether the code can be drawn at all, and both are exact
//! rather than approximate:
//!
//! * a terminal cell is about twice as tall as it is wide, so two module rows
//!   share one text row (a half block, `▀▄█`), and a module takes
//!   [`COLUMNS_PER_MODULE`] columns to stay square;
//! * the quiet zone is [`QUIET_ZONE`] modules on every side.
//!
//! [`QrMatrix::columns`] and [`QrMatrix::rows`] are the whole arithmetic, and
//! [`QrMatrix::fits`] compares them against a rectangle. When it does not fit,
//! the overlay shows the URL instead and says plainly that the code cannot be
//! drawn at this size and how much room it wants — a scannable-looking square
//! of clipped modules would be worse than none.

/// Modules of quiet zone a decoder requires on every side.
pub const QUIET_ZONE: usize = 4;

/// Columns one module occupies. Two keeps the module square in a terminal cell
/// that is roughly twice as tall as it is wide.
pub const COLUMNS_PER_MODULE: u16 = 2;

/// Module rows drawn per text row, via a half-block glyph.
pub const MODULE_ROWS_PER_TEXT_ROW: usize = 2;

/// Both module rows of a cell dark.
pub const FULL_BLOCK: char = '█';
/// Only the upper module row dark.
pub const UPPER_BLOCK: char = '▀';
/// Only the lower module row dark.
pub const LOWER_BLOCK: char = '▄';
/// Neither module row dark.
pub const LIGHT_GLYPH: char = '░';

/// A parsed QR module matrix.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QrMatrix {
    size: usize,
    /// `size * size` modules, row-major. `true` is dark.
    dark: Vec<bool>,
}

impl QrMatrix {
    /// Parses the daemon's rows.
    ///
    /// Rejects anything that is not a non-empty square of `0`/`1`. A matrix
    /// this client cannot read exactly is not drawn as though it could: half a
    /// QR code is not a QR code, and a wrong one sends the phone to the wrong
    /// place with total confidence.
    pub fn parse(rows: &[String]) -> Result<Self, String> {
        let Some(first) = rows.first() else {
            return Err("the daemon sent no QR rows".to_string());
        };
        let size = first.chars().count();
        if size == 0 {
            return Err("the daemon sent an empty QR row".to_string());
        }
        if rows.len() != size {
            return Err(format!(
                "the QR matrix is {} rows by {} columns, which is not a square",
                rows.len(),
                size
            ));
        }

        let mut dark = Vec::with_capacity(size * size);
        for (index, row) in rows.iter().enumerate() {
            let mut columns = row.chars();
            for column in 0..size {
                let Some(cell) = columns.next() else {
                    return Err(format!("QR row {index} is shorter than {size} columns"));
                };
                match cell {
                    '0' => dark.push(false),
                    '1' => dark.push(true),
                    other => {
                        return Err(format!(
                            "QR row {index} column {column} is {other:?}, which is not 0 or 1"
                        ))
                    }
                }
            }
            if columns.next().is_some() {
                return Err(format!("QR row {index} is longer than {size} columns"));
            }
        }
        Ok(Self { size, dark })
    }

    /// Whether the module at `(row, column)` is dark. Out-of-range reads are
    /// light, which is what the quiet zone needs.
    pub fn is_dark(&self, row: usize, column: usize) -> bool {
        row < self.size && column < self.size && self.dark[row * self.size + column]
    }

    /// Modules per side including the quiet zone.
    fn span(&self) -> usize {
        self.size + 2 * QUIET_ZONE
    }

    /// Terminal columns the code needs: the quiet zone, then
    /// [`COLUMNS_PER_MODULE`] columns per module.
    pub fn columns(&self) -> u16 {
        (self.span() * COLUMNS_PER_MODULE as usize) as u16
    }

    /// Text rows the code needs: [`MODULE_ROWS_PER_TEXT_ROW`] module rows per
    /// text row, rounded up for the odd span every QR version plus its quiet
    /// zone produces.
    pub fn rows(&self) -> u16 {
        self.span().div_ceil(MODULE_ROWS_PER_TEXT_ROW) as u16
    }

    /// Whether the code fits in a `width` × `height` rectangle. Exactly equal
    /// is enough; one column or one row short is not.
    pub fn fits(&self, width: u16, height: u16) -> bool {
        self.columns() <= width && self.rows() <= height
    }

    /// The characters one text row is made of, each with whether its module is
    /// dark.
    ///
    /// The glyph encodes *which half* of the cell the dark module occupies, so
    /// `▀` and `▄` differ even where the block itself is drawn as the cell's
    /// background. That is what makes the matrix legible with colour
    /// suppressed, where the two halves would otherwise be one grey smear.
    pub fn line(&self, text_row: usize) -> Vec<(char, bool)> {
        let upper_row = text_row * MODULE_ROWS_PER_TEXT_ROW;
        (0..self.columns())
            .map(|column| {
                let module_column = column as usize / COLUMNS_PER_MODULE as usize;
                let upper = self.is_padded_dark(upper_row, module_column);
                let lower = self.is_padded_dark(upper_row + 1, module_column);
                let dark = upper || lower;
                let glyph = match (upper, lower) {
                    (false, false) => LIGHT_GLYPH,
                    (true, true) => FULL_BLOCK,
                    (true, false) => UPPER_BLOCK,
                    (false, true) => LOWER_BLOCK,
                };
                (glyph, dark)
            })
            .collect()
    }

    /// One module of the quiet-zone-padded matrix. Everything outside the
    /// padded square is light, which is exactly what a quiet zone is.
    fn is_padded_dark(&self, row: usize, column: usize) -> bool {
        let row = row.checked_sub(QUIET_ZONE);
        let column = column.checked_sub(QUIET_ZONE);
        match (row, column) {
            (Some(row), Some(column)) => self.is_dark(row, column),
            _ => false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn square(size: usize) -> Vec<String> {
        (0..size)
            .map(|row| {
                (0..size)
                    .map(|column| if (row + column) % 2 == 0 { '1' } else { '0' })
                    .collect()
            })
            .collect()
    }

    #[test]
    fn a_valid_matrix_parses_and_reports_its_geometry() {
        let matrix = QrMatrix::parse(&square(21)).expect("a 21x21 matrix is square");
        // The top-left module of the checkerboard is dark, the one beside it is
        // light, and the geometry is 21 modules plus a 4-module quiet zone.
        assert!(matrix.is_dark(0, 0));
        assert!(!matrix.is_dark(0, 1));
        assert_eq!(matrix.columns(), 58);
        assert_eq!(matrix.rows(), 15);
    }

    #[test]
    fn a_non_square_matrix_is_refused() {
        let rows = vec!["0110".to_string(), "1001".to_string(), "0110".to_string()];
        let reason = QrMatrix::parse(&rows).expect_err("3 rows of 4 is not a square");
        assert!(reason.contains("not a square"), "{reason}");
    }

    #[test]
    fn a_matrix_with_a_foreign_character_is_refused() {
        let rows = vec![
            "01x0".to_string(),
            "1001".to_string(),
            "0110".to_string(),
            "0000".to_string(),
        ];
        let reason = QrMatrix::parse(&rows).expect_err("x is not a module");
        assert!(reason.contains('x'), "{reason}");
    }

    #[test]
    fn an_empty_matrix_is_refused() {
        let reason = QrMatrix::parse(&[]).expect_err("no rows at all");
        assert!(reason.contains("no QR rows"), "{reason}");
    }

    #[test]
    fn a_row_of_the_wrong_length_is_refused() {
        // Square by row count, ragged by row width: the square check passes and
        // the per-row length check is what catches it.
        let rows = vec!["011".to_string(), "01".to_string(), "011".to_string()];
        let reason = QrMatrix::parse(&rows).expect_err("rows must be equal length");
        assert!(reason.contains("shorter"), "{reason}");
    }

    /// Version 1 is 21 modules; with a four-module quiet zone that is 29 across,
    /// which is 58 columns and 15 text rows.
    #[test]
    fn the_fit_arithmetic_is_exact() {
        let matrix = QrMatrix::parse(&square(21)).expect("square");
        assert_eq!(matrix.columns(), 58);
        assert_eq!(matrix.rows(), 15);
        assert!(matrix.fits(58, 15), "exactly enough room is enough");
        assert!(!matrix.fits(57, 15), "one column short is not enough");
        assert!(!matrix.fits(58, 14), "one row short is not enough");
    }

    #[test]
    fn an_odd_span_rounds_the_last_text_row_up() {
        // 25 + 8 = 33 module rows, which is 17 text rows with one to spare.
        let matrix = QrMatrix::parse(&square(25)).expect("square");
        assert_eq!(matrix.columns(), 66);
        assert_eq!(matrix.rows(), 17);
    }

    #[test]
    fn the_quiet_zone_is_light_on_every_side() {
        let matrix = QrMatrix::parse(&square(9)).expect("square");
        let first = matrix.line(0);
        let last = matrix.line(matrix.rows() as usize - 1);
        // The top row is entirely quiet zone, so nothing in it is dark.
        assert!(first.iter().all(|(_, dark)| !dark));
        assert!(last.iter().all(|(_, dark)| !dark));
    }

    #[test]
    fn every_line_is_exactly_as_wide_as_the_fit_check_says() {
        let matrix = QrMatrix::parse(&square(21)).expect("square");
        for row in 0..matrix.rows() as usize {
            assert_eq!(matrix.line(row).len(), matrix.columns() as usize);
        }
    }

    #[test]
    fn a_line_carries_both_a_glyph_and_whether_the_module_is_dark() {
        // A matrix of known shape: the first module row is dark, the rest are
        // light. So the first text row of real modules has a dark upper half
        // and a light lower half, and the text row after it is all light.
        let mut rows = vec!["111111".to_string()];
        rows.extend((0..5).map(|_| "000000".to_string()));
        let matrix = QrMatrix::parse(&rows).expect("square");
        let first_text_row = QUIET_ZONE / MODULE_ROWS_PER_TEXT_ROW;
        let first_cell = QUIET_ZONE * COLUMNS_PER_MODULE as usize;

        let line = matrix.line(first_text_row);
        assert!(line[first_cell].1, "a dark upper half is a dark module");
        assert_eq!(line[first_cell].0, UPPER_BLOCK);

        // The same module column, one text row down: both halves are light.
        let below = matrix.line(first_text_row + 1);
        assert!(!below[first_cell].1);
        assert_eq!(below[first_cell].0, LIGHT_GLYPH);
        assert_ne!(
            line[first_cell].0, below[first_cell].0,
            "dark and light must differ by glyph, not by colour alone"
        );
    }
}
