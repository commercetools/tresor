# tresor  🔒 

*tesor* ( **[tʁeˈzoːɐ̯]** ) is a Scala library to access secrets (credentials etc.) from different sources.

## Features
 - Provider for AES-256 encryption
 - Providers for secrets from Hashicorp Vault engines (currently KV, Database, AWS)
 - Integration with [cats-effect](https://github.com/typelevel/cats-effect)
 - Supports Scala 2.11, 2.12
 - [Apache 2.0 licensed](LICENSE)

## Goals
Provide an **idiomatic** access to *mainstream* ways of working with secrets in **Scala**

Follow a **light-weight** approach, **avoid** feature creep and **dependencies** where possible

## Non-Goals
tresor will **not**

... be a full-blown *authentication* library

... support all *Vault* engines or *API* calls (should be easy enough to extend)

... integrate with *other high-level libraries* (this could be done in extra modules though)

@@@ index

* [Setup](setup.md)
* [Examples](examples.md)

@@@
