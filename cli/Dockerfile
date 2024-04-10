FROM rust:1.76 as builder
WORKDIR /usr/src/tresor
COPY . .
RUN cargo install --path .

FROM debian:bullseye-slim
RUN apt-get update && apt-get install -y openssl ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=builder /usr/local/cargo/bin/tresor-cli /usr/local/bin/tresor
CMD ["tresor"]
