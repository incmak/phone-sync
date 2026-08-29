package main

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"
)

const defaultHealthcheckURL = "http://127.0.0.1:8080/health/ready"

func runHealthcheckCommand(arguments []string, client *http.Client) error {
	flags := flag.NewFlagSet("healthcheck", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	target := flags.String("url", defaultHealthcheckURL, "relay readiness URL")
	if err := flags.Parse(arguments); err != nil || flags.NArg() != 0 {
		return errors.New("invalid healthcheck arguments")
	}
	parsed, err := url.ParseRequestURI(*target)
	if err != nil || parsed.Host == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
		return errors.New("healthcheck URL must be an absolute HTTP or HTTPS URL")
	}

	if client == nil {
		client = &http.Client{}
	}
	healthClient := *client
	if healthClient.Timeout <= 0 || healthClient.Timeout > 5*time.Second {
		healthClient.Timeout = 3 * time.Second
	}
	healthClient.CheckRedirect = func(_ *http.Request, _ []*http.Request) error {
		return http.ErrUseLastResponse
	}

	request, err := http.NewRequest(http.MethodGet, parsed.String(), nil)
	if err != nil {
		return errors.New("create healthcheck request")
	}
	response, err := healthClient.Do(request)
	if err != nil {
		return errors.New("request relay readiness")
	}
	defer response.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("relay readiness returned HTTP %d", response.StatusCode)
	}
	return nil
}
