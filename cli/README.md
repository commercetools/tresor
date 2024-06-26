## tresor cli

an opinionated vault cli

### Features

- multiple environments support
- mount and path templating
- environment token helper
- metadata support
- sync between paths

tresor cli is not a vault cli wrapper, it is more like a subset of the original vault cli

### Run

#### Docker
```sh
alias tresor="docker run -p 8250:8250 -v ~/.config:/home/tresor/.config adrobisch/tresor:latest tresor"
tresor help
```

#### Locally
use `cargo install` or see [install.sh](install.sh)

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
# first run will create a config, add your environments there
tresor config
tresor login env
tresor token env

# eval vault env variables into current shell
`tresor token env`

# given the example config
# this will list kv2/repo/some_service/env/some_path/prod1
tresor list env prod1 -s some_service

# this will get kv2/repo/some_service/env/some_path/prod1/.
tresor list env prod1 -s some_service -p .

# this will get kv2/repo/some_service/env/some_path/prod1/some_path
tresor get env prod1 -s some_service -p some_path

# check value mappings in context prod1
tresor sync env prod1
# check value mappings all contexts
tresor sync env *

# apply value mappings including metadata
tresor sync env --apply --metadata-rotation=true
...
```

#### Config

```yaml
# default owner for metadata
defaultOwner: my-team
# default mount template if not specified in command
defaultMountTemplate: "default"
# default path template if not specified in command
defaultPathTemplate: "default"
# additional metadata for the sync command
# supports templating
defaultMetadata:
  last-sync: "{{now}}"
# named templates
mountTemplates:
  default: kv2/repo/{{service}}
  other: kv2/other-repo/{{service}}
pathTemplates:
  default: "{{environment}}/{{path}}/{{context}}"
  replaced: "{{environment}}/{{context}}/{{ path|replace("-", "_") }}-secret"
  variable: "{{foo}}/{{context}}/{{path}}-secret"
environments:
  - name: env
    vaultAddress: http://localhost:8200
    contexts:
      - name: prod1
        variables:
          foo: bar
    authMount: null
# mappings to sync between different mounts / paths
mappings:
  - source: null
      mount: default
      path: replaced
      key: SECRET_FIELD
    # or instead of source:
    # value: "{{foo}}-secret"
    target:
      mount: other
      path: variable
      key: OTHER_FIELD
    # metadata to be added/to overwrite the default values
    metadata:
      owner: custom-owner
    # conditionally process this mapping, this can be a jinja expression
    # when: false
```

Note that you can use the context variables and the `service` and `path` args in the mount and path templates.

`tresor` is using [minijinja](https://github.com/mitsuhiko/minijinja) for templating.
