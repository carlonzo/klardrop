## Test

Receive
```shell
socat - UDP-RECV:2121
```

Send
```shell
socat - UDP-DATAGRAM:255.255.255.255:2121,broadcast
```