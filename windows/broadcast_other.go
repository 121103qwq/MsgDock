//go:build !windows

package main

import (
	"fmt"
	"net"
	"syscall"
)

func sendLanBroadcast(data []byte, port int) error {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.ParseIP(localIPv4()), Port: 0})
	if err != nil {
		return err
	}
	defer conn.Close()
	raw, err := conn.SyscallConn()
	if err != nil {
		return err
	}
	var sockErr error
	err = raw.Control(func(fd uintptr) {
		sockErr = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_BROADCAST, 1)
	})
	if err != nil {
		return err
	}
	if sockErr != nil {
		return fmt.Errorf("enable broadcast: %w", sockErr)
	}
	_, err = conn.WriteToUDP(data, &net.UDPAddr{IP: net.IPv4bcast, Port: port})
	return err
}
