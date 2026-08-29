package main

import (
	"errors"
	"net"
	"sync"
)

type limitListener struct {
	net.Listener
	permits   chan struct{}
	closed    chan struct{}
	closeOnce sync.Once
}

func newLimitListener(listener net.Listener, maximum int) (*limitListener, error) {
	if listener == nil || maximum <= 0 {
		return nil, errors.New("listener and positive connection limit are required")
	}
	return &limitListener{
		Listener: listener,
		permits:  make(chan struct{}, maximum),
		closed:   make(chan struct{}),
	}, nil
}

func (l *limitListener) Accept() (net.Conn, error) {
	select {
	case l.permits <- struct{}{}:
	case <-l.closed:
		return nil, net.ErrClosed
	}
	connection, err := l.Listener.Accept()
	if err != nil {
		<-l.permits
		return nil, err
	}
	return &limitedConnection{Conn: connection, release: func() { <-l.permits }}, nil
}

func (l *limitListener) Close() error {
	l.closeOnce.Do(func() { close(l.closed) })
	return l.Listener.Close()
}

type limitedConnection struct {
	net.Conn
	releaseOnce sync.Once
	release     func()
}

func (c *limitedConnection) Close() error {
	err := c.Conn.Close()
	c.releaseOnce.Do(c.release)
	return err
}
