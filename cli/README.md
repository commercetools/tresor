## tresor cli

an opinionated vault cli

### Features

- multiple environments support
- mount and path templating
- environment token helper
- metadata support

tresor cli is not a vault cli wrapper, it is more like a subset of the original vault cli

### Install

currently you need to build from source, use `cargo install` or see [install.sh](install.sh)

### Usage

```sh
Usage: tresor <COMMAND>

Commands:
  login   login and store the token in the environment config
  list    list paths in context
  get     get values in context
  set     set values using the put method
  patch   set values using the patch method
  config  show current config without tokens
  token   print the current token of the environment
  sync    sync the value mappings in the environment configuration
  help    Print this message or the help of the given subcommand(s)

Options:
  -h, --help     Print help
  -V, --version  Print version
```

### Examples

```sh
# first run will create a config, add you environments there
tresor config
tresor login env
tresor token env

# eval vault env variables into current shell
`tresor token env`

# given the example config
# this will list kv2/repo/some_service/env/some_path/prod1
tresor list env prod1 -s some_service

# this will get kv2/repo/some_service/env/some_path/prod1/some_path
tresor get env prod1 -s some_service -p /some_path

# check value mappings
tresor sync env

# apply value mappings including metadata
tresor sync env --apply --metadata-rotation=true
...
```

#### Config

```yaml
defaultOwner: my-team
# templates for read and set
mountTemplate: kv2/repo/{{service}}
pathTemplate: "{{environment}}/{{path}}/{{context}}"
environments:
  - name: env
    vaultAddress: http://localhost:8200
    contexts:
      - name: production
        variables:
          alias: prod1
          foo: bar
    authMount: null
# mappings to sync between different mounts / paths
mappings:
  - source: null
      mount: kv2/repo/{{service}}
      path: "{{environment}}/{{context}}/{{path}}-secret"
      key: SECRET_FIELD
    # or instead of source
    # value: "{{foo}}-secret"
    target:
      mount: kv2/other-repo/{{service}}
      path: "{{alias}}/{{context}}/{{path}}-secret"
      key: OTHER_FIELD
    # skip processing this mapping
    # skip: false
```

Note that you can use the context variables and the `service` and `path` args in the mount and path templates.

`tresor` is using [minijinja](https://github.com/mitsuhiko/minijinja) for templating.
