# Examples

## Using the Vault Key Value Engine 

The KV engine in vault lets you store arbitrary secrets in a JSON-object like manner.
This will create non-refreshable (as in *always valid*) leases:

@@snip [KVExample.scala](../../test/scala/examples/KVExample.scala){#kv-example}

## Using the AWS Engine with auto-refresh

The AWS engine create refreshable leases for which a reference can be used for storing. 
Usually you would create the reference in a **safe** way during application bootstrap, this has been omitted here: 

@@snip [AWSExample.scala](../../test/scala/examples/AWSExample.scala){#aws-example}