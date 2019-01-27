# tresor 🔒 **[tʁeˈzoːɐ̯]**

[![travis](https://travis-ci.org/adrobisch/tresor.svg?branch=master)](https://travis-ci.org/adrobisch/tresor)
[![latest release](https://img.shields.io/maven-central/v/com.drobisch/tresor_2.12.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:com.drobisch%20AND%20a:tresor*)

A Scala library to access secrets (credentials etc.) from different sources.

## Example

```scala
import com.drobisch.tresor._
import com.drobisch.tresor.vault._

val vaultConfig = VaultConfig(apiUrl = "http://vault-host:8200/v1", token = "vault-token")

Tresor(provider = KV[cats.effect.IO]).read(KeyValueContext(key = "treasure", vaultConfig)) // IO[vault.Lease]
```

# Features
 - Provider for AES-256 encryption
 - Providers for secrets from Hashicorp Vault engines (currently KV, AWS)
 - Integration with [cats-effect](https://github.com/typelevel/cats-effect)
 - Supports Scala 2.11, 2.12
 
# License

[Apache License 2.0](LICENSE)