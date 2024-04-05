use std::fmt::Display;

use console::Style;
use serde_json::Value;
use tabled::settings::{object::Columns, Style as TableStyle, Width};

use crate::error::CliError;

pub struct Console {}

impl Console {
    pub fn error<T: Display>(text: T) -> String {
        Style::new().red().apply_to(text).to_string()
    }

    pub fn warning<T: Display>(text: T) -> String {
        Style::new().yellow().apply_to(text).to_string()
    }

    pub fn success<T: Display>(text: T) -> String {
        Style::new().green().apply_to(text).to_string()
    }

    pub fn highlight<T: Display>(text: T) -> String {
        Style::new()
            .fg(console::Color::Cyan)
            .apply_to(text)
            .to_string()
    }

    pub fn emph<T: Display>(text: T) -> String {
        Style::new().white().apply_to(text).to_string()
    }
}

pub fn json_to_table_string(value: &Value, truncate_first_col: bool) -> Result<String, CliError> {
    let mut table = json_to_table::json_to_table(&serde_json::to_value(value)?).into_table();

    table.with(TableStyle::modern());

    if truncate_first_col {
        table.modify(Columns::new(1..), Width::truncate(8).suffix("..."));
    }

    Ok(Console::emph(table.to_string()))
}
