"""The phone link: transports, framing and the daemon's link identity.

Two transports carry the same device protocol to the Android bridge: an iroh stream
(`iroh.py`, dialled by endpoint id through relays or hole punching) and a TLS WebSocket
(`websocket.py`, for phones that reach the daemon by address). `DeviceHub` in
`phonxiety.device` speaks the protocol over whichever one is attached. Which parts use a
library and which stay ours is in docs/network-research.md.
"""
