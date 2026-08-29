package server

import "github.com/twinotify/relay/internal/store"

const responderConfirmationDomain = "twinotify-pair-confirm-b-v1\n"

func responderConfirmationMessage(pending *store.PendingPair, initiatorSignature []byte) []byte {
	message := make([]byte, 0, len(responderConfirmationDomain)+len(pending.PairToken)+
		len(pending.AEncPubkey)+len(pending.ASignPubkey)+len(pending.BEncPubkey)+
		len(pending.BSignPubkey)+len(initiatorSignature))
	message = append(message, responderConfirmationDomain...)
	message = append(message, pending.PairToken...)
	message = append(message, pending.AEncPubkey...)
	message = append(message, pending.ASignPubkey...)
	message = append(message, pending.BEncPubkey...)
	message = append(message, pending.BSignPubkey...)
	return append(message, initiatorSignature...)
}
