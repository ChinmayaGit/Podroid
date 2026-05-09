# Podroid X11 environment — sourced by /etc/profile for login shells.
# Apps launched from the user's shell inherit a working DISPLAY and
# PULSE_SERVER without any setup, so `xeyes` / `firefox` etc. just work.
export DISPLAY=:0
export PULSE_SERVER=tcp:127.0.0.1:4713
