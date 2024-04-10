#!/bin/sh
cargo build --release && sudo cp target/release/tresor /usr/local/bin/tresor
echo "you can use tresor now"
