# tresor 🔒 **[tʁeˈzoːɐ̯]**

[![travis](https://travis-ci.org/adrobisch/tresor.svg?branch=master)](https://travis-ci.org/adrobisch/tresor)
[![latest release](https://img.shields.io/maven-central/v/com.drobisch/tresor_2.12.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:com.drobisch%20AND%20a:tresor*)
[![codecov](https://codecov.io/gh/adrobisch/tresor/branch/master/graph/badge.svg)](https://codecov.io/gh/adrobisch/tresor)

A Scala library to access secrets (credentials etc.) from different sources.

# Features
 - Provider for AES-256 encryption
 - Providers for secrets from Hashicorp Vault engines (currently KV, Database, AWS)
 - Integration with [cats-effect](https://github.com/typelevel/cats-effect)
 - Supports Scala 2.11, 2.12
 
# Documentation

see the [documentation site](https://adrobisch.github.io/tresor) for examples etc.
  
# License

[Apache License 2.0](LICENSE)