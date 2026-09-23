These certificates and the private key are **public test fixtures only**. They
are used by local loopback HTTPS tests and are excluded from distributed packages.
The Android app generates its own non-exportable key in Android Keystore.

`server-public.pem` is an independently extracted SPKI for testing the Python
certificate parser. The test certificate has a deliberately long validity period
to avoid time-dependent fixture failures; production trust is bound to the key.
