# tresor 🔒

**[tʁeˈzoːɐ̯]**

A Scala library to access secrets (credentials etc.) from different sources.

## Example

```scala
import com.drobisch.tresor._
import com.drobisch.tresor.vault._

val context = KeyValueContext(VaultConfig(apiUrl = "http://vault-host:8200/v1", token = "vault-token"))

Tresor.secret("precious", context)(provider = KV) // IO[vault.Lease]
```

# Features

 - Providers for secrets from Hashicorp Vault engines (currently KV)
 - Integration with [cats-effect](https://github.com/typelevel/cats-effect) `IO`
 - Supports Scala 2.11, 2.12
 
# License

[Apache License 2.0](LICENSE)