use std::{collections::HashSet, sync::Arc};

use minijinja::{
    value::{StructObject, ValueKind},
    Value,
};
use std::sync::Mutex;

struct TrackedContext {
    enclosed: Value,
    undefined: Arc<Mutex<HashSet<String>>>,
}

impl StructObject for TrackedContext {
    fn get_field(&self, name: &str) -> Option<Value> {
        match self
            .enclosed
            .get_attr(name)
            .ok()
            .filter(|x| !x.is_undefined())
        {
            Some(rv) => Some(rv),
            None => {
                let mut undefined = self.undefined.lock().unwrap();
                if !undefined.contains(name) {
                    undefined.insert(name.to_string());
                }
                None
            }
        }
    }

    fn fields(&self) -> Vec<Arc<str>> {
        if self.enclosed.kind() == ValueKind::Map {
            if let Ok(keys) = self.enclosed.try_iter() {
                return keys.filter_map(|x| Arc::<str>::try_from(x).ok()).collect();
            }
        }
        Vec::new()
    }
}

pub fn track_context(ctx: Value) -> (Value, Arc<Mutex<HashSet<String>>>) {
    let undefined = Arc::new(Mutex::default());
    (
        Value::from_struct_object(TrackedContext {
            enclosed: ctx,
            undefined: undefined.clone(),
        }),
        undefined,
    )
}
