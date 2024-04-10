FROM rust:1.76 as builder
WORKDIR /usr/src/tresor
COPY . .
RUN cargo install --path .

FROM debian:bookworm-slim
RUN apt-get update -y && apt-get install -y openssl ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=builder /usr/local/cargo/bin/tresor-cli /usr/local/bin/tresor

RUN useradd -ms /bin/bash tresor
USER tresor:tresor
WORKDIR /home/tresor

# port for the login callback
EXPOSE 8250
CMD ["tresor"]
