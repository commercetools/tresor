## tresor cli

an opinionated vault cli

### Features

- multiple environments support
- mount and path templating
- environment token helper
- metadata support

tresor cli is not a vault cli wrapper, it is more like a subset of the original vault cli

### Install

currently you need to build from source, see [install.sh](install.sh)

### Usage

```
Usage: tresor <COMMAND>

Commands:
  login
  list
  get
  set
  config
  token        print the current token of the environment
  help         Print this message or the help of the given subcommand(s)

Options:
  -h, --help     Print help
  -V, --version  Print version
```

### Examples

```
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
tresor sync prod --apply --metadata-rotation=true
...
```

#### Config

```yaml
defaultOwner: my-team
mountTemplate: kv2/repo/{{service}}
pathTemplate: "{{environment}}/some_path/{{context}}"
environments:
  - name: env
    vaultAddress: http://localhost:8250
    contexts:
      - prod1
    # allows to sync between values
    mappings:
      - source:
          mount: kv2/repo/something
          path: some-path
          key: some-key
        # or instead of source:
        # value: some_value
        target:
          mount: kv2/repo/other
          path: other-path
          key: other-key
        # skip: true
```
