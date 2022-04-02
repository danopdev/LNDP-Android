# Local Network Document Provider (Android client) #

I needed a simple and fast mechanism to copy files between my smartphone and my laptop.
I tried to use FTP or SAMBA but there was always something that didn't work or it was too slow.

The comunication is done over HTTP and on Android side it use Document Provider API.
Using Document Provider allow to open server's files from other Android application (Ex: you can open a remote image using Snapseed).

The Android application:
* expose server files using Document Provider
* have a simple interface that allow you to copy file
* allow you to expose files as a simple read-only sever (this way you can transfer files directly between two android devices)

For the linux server check [LNDP-Server](https://github.com/danopdev/LNDP-Server) project.

## TODO ##

Try to use HTTPS.
