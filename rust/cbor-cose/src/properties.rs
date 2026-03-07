use ahash::AHashMap;
use std::sync::RwLock;

static PROPERTIES: RwLock<Option<AHashMap<String, String>>> = RwLock::new(None);

pub fn get_property<F, R>(name: &str, f: F) -> Option<R>
where
    F: FnOnce(&str) -> R,
{
    // Avoid panics inside Zygote
    if let Ok(cache) = PROPERTIES.read() {
        cache.as_ref().and_then(|c| c.get(name).map(|s| f(s.as_str())))
    } else {
        None
    }
}

pub fn set_property(name: &str, value: &str) {
    if let Ok(mut cache) = PROPERTIES.write() {
        let map = cache.get_or_insert_with(AHashMap::new);
        map.insert(name.to_string(), value.to_string());
    }
}

pub fn clear_properties() {
    if let Ok(mut cache) = PROPERTIES.write() {
        if let Some(map) = cache.as_mut() {
            map.clear();
        }
    }
}
