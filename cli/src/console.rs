use std::fmt::Display;

use console::Style;

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
}
