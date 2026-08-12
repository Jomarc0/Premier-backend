# Passenger Support Chatbot Audit

## Problems found

- The previous chat controller treated every `lost` or `missing` phrase as a generic sensitive escalation, so passengers never received the defined lost-card procedure.
- A Dialogflow outage could send known support phrases to Gemini, and the local fallback contained unsupported promises such as fixed update times, named payment methods, and a phone number.
- Ticket-status lookup scanned every support ticket and matched on card number. That was inefficient and did not use the authenticated passenger relationship as the authorization boundary.
- Passenger support-ticket endpoints only created tickets; there was no authenticated API to list or retrieve the passenger's own tickets.
- Ticket numbers used `count + 1`, which can collide during concurrent submissions.
- Two concurrent resolution requests could both pass the open-ticket check and send duplicate resolution emails.
- No support-message entity, repository, controller, or WebSocket handler exists in this repository. The claimed existing “Support Chat” implementation is therefore not available here to audit. It must remain the source of ticket messages; do not build a second chat system in the chatbot.

## Code changes made

- `ChatbotController` now gives authenticated backend data priority for ticket-status requests, uses the fixed lost-card procedure, preserves Dialogflow replies for recognized requests, and invokes Gemini only for unsupported fallback questions.
- `PremierBotKnowledgeService` now distinguishes top-up help from top-up problems and fetches support tickets only by authenticated `passenger_id`.
- `SupportTicketRepository` now has passenger-scoped lookup and a write-lock lookup for ticket resolution.
- `SupportTicketService` now exposes passenger-owned list/detail operations, uses non-count-based unique ticket references, and locks the resolve operation before status/email work.
- `PublicSupportTicketController` now exposes `GET /api/passenger/support-tickets` and `GET /api/passenger/support-tickets/{id}`. Both are authenticated by the existing security configuration and return only the caller's tickets.

The preserved lifecycle is `PENDING -> IN_REVIEW -> RESOLVED` (with `REJECTED` as an existing valid close state). Resolution email is backend-only. The pessimistic lock ensures a second simultaneous resolve sees the already closed ticket and cannot send another resolution email.

## Exact changes to make in Dialogflow ES

Use existing intents where their purpose matches; do not create a duplicate intent with a different name. Static intents must **not** enable webhook fulfillment. Only the ticket-status intent needs webhook fulfillment after a secure authenticated backend webhook is available. The current chat endpoint already obtains status directly after intent detection, so do not send user IDs or ticket IDs from Dialogflow.

### Intent: `General_Support`

- Current problem: confirm it does not overlap with top-up, lost-card, or ticket phrases.
- Action: modify or reuse for broad passenger help.
- Training phrases: `I need help`, `Can you help me`, `I need assistance`, `I need support`, `How can I use the system`.
- Parameters: none.
- Input / output context: none.
- Fulfillment: do not enable webhook fulfillment.
- Response: `I can help with top-ups, lost-card procedures, and support tickets. Tell me what you need help with.`
- Test phrase: `I need assistance.`

### Intent: `Top_Up_Help`

- Current problem: remove payment-provider names, update-time guarantees, or procedures not used by this application.
- Action: modify or reuse.
- Training phrases: `How do I top up`, `How do I reload my card`, `How can I add money`, `How can I add balance`, `Where can I top up`.
- Parameters: none.
- Input / output context: none.
- Fulfillment: do not enable webhook fulfillment.
- Response: `Open your passenger dashboard, choose a top-up amount, continue to the PayMongo checkout, and complete the payment. After it is verified, check your updated balance. If it does not update, I can help you open a support ticket.`
- Test phrase: `How can I reload my card?`

### Intent: `Top_Up_Problem`

- Current problem: keep it separate from the “how to top up” informational intent.
- Action: modify or reuse.
- Training phrases: `My top-up failed`, `My balance did not update`, `I topped up but my balance is wrong`, `Payment was deducted but balance did not increase`, `My top up is pending`.
- Parameters: none; do not collect payment secrets in Dialogflow.
- Input / output context: none.
- Fulfillment: do not enable webhook fulfillment for the static escalation response.
- Response: `I am sorry you are experiencing a top-up issue. Check that the payment was completed and verified. If your balance is still incorrect, please submit a support ticket and include the payment reference number.`
- Test phrase: `My payment was deducted but my balance did not increase.`

### Intent: `Lost_Card`

- Current problem: ensure lost-card guidance is not confused with an account/card action.
- Action: modify or reuse.
- Training phrases: `I lost my card`, `My RFID card is missing`, `I cannot find my card`, `What should I do if I lose my card`, `My card was stolen`.
- Parameters: none.
- Input / output context: none.
- Fulfillment: do not enable webhook fulfillment.
- Response: `Lost or stolen RFID cards require admin review before freezing or replacement. Please submit a support ticket immediately so staff can protect the card and guide you through the next steps.`
- Test phrase: `I lost my RFID card.`

### Intent: `Lost_Card_Procedure`

- Current problem: only retain if it is distinct from `Lost_Card`; otherwise merge it into that existing intent.
- Action: modify or merge; do not create a duplicate.
- Training phrases: `How do I report a lost card`, `Can I replace my lost card`, `What happens to my balance`, `Tell me the lost card procedure`.
- Parameters: none.
- Input / output context: none.
- Fulfillment: do not enable webhook fulfillment.
- Response: `Submit a lost-card support ticket as soon as possible. An admin reviews the request and will guide any card protection or replacement steps. The chatbot cannot freeze a card, replace a card, or promise a balance transfer.`
- Test phrase: `How do I report a lost card?`

### Intent: `Support_Request`

- Current problem: include human-escalation wording, but exclude ticket-status wording.
- Action: modify or reuse.
- Training phrases: `I still need help`, `This did not solve my problem`, `I want to contact support`, `I need to report this issue`, `I want to talk to support`.
- Parameters: none.
- Input / output context: none.
- Fulfillment: do not enable webhook fulfillment.
- Response: `I can help you submit a support ticket so our support team can investigate. Would you like to create a support ticket?`
- Test phrase: `I still need help.`

### Intent: `Create_Ticket`

- Current problem: use this only after an explicit request to submit a ticket.
- Action: modify or reuse.
- Training phrases: `Yes, create a ticket`, `I want to submit a ticket`, `Open a support ticket`, `Report my problem`.
- Parameters: none.
- Input context: optional `awaiting_ticket_confirmation` if you keep a confirmation turn.
- Output context: none.
- Fulfillment: do not enable webhook fulfillment. The client should open the existing authenticated ticket form (`POST /api/passenger/support-tickets`).
- Response: `Please complete the secure support-ticket form. Your signed-in account will be associated with the ticket automatically.`
- Test phrase: `Yes, create a ticket.`

### Intent: `Ticket_Status`

- Current problem: never return a guessed status or accept another passenger's ticket information.
- Action: modify or reuse.
- Training phrases: `What is the status of my ticket`, `Check my support request`, `Any update on my case`, `Is my ticket resolved`, `Track my ticket`.
- Parameters: none. Do not add user ID, card number, or ticket ID parameters for authorization.
- Input / output context: none.
- Fulfillment: **enable webhook fulfillment only if the webhook uses the authenticated backend identity.** Otherwise leave it disabled; this application's chat backend already performs the secure lookup after Dialogflow detects the intent.
- Response: webhook response only; status must come from the authenticated passenger's ticket record.
- Test phrase: `What is the status of my ticket?`

### Intent: `Confirmation`

- Current problem: do not let a bare `yes` trigger an action outside an explicit ticket-confirmation context.
- Action: modify or reuse.
- Training phrases: `Yes`, `Yes please`, `Okay`, `Go ahead`.
- Parameters: none.
- Input context: `awaiting_ticket_confirmation` only.
- Output context: none.
- Fulfillment: do not enable webhook fulfillment.
- Response: `Please complete the secure support-ticket form.`
- Test phrase: `Yes please.`

### Intent: `Cancel`

- Current problem: prevent cancellation text being interpreted as a support-ticket creation request.
- Action: modify or reuse.
- Training phrases: `Cancel`, `Never mind`, `No thanks`, `I do not need help anymore`.
- Parameters: none.
- Input context: `awaiting_ticket_confirmation` only.
- Output context: none.
- Fulfillment: do not enable webhook fulfillment.
- Response: `No problem. If you need help later, I can assist with top-ups, lost cards, and support tickets.`
- Test phrase: `Never mind.`

### Intent: Default Fallback

- Current problem: do not make claims or actions outside passenger support.
- Action: modify existing fallback.
- Training phrases: not applicable.
- Parameters / contexts: none.
- Fulfillment: do not enable webhook fulfillment.
- Response: `I am sorry, I could not understand your request. I can help with general passenger assistance, top-up concerns, lost-card procedures, and support tickets. Please try asking again.`
- Test phrase: `Can you change my account details?`

## Final intent structure and flows

`General_Support`, `Top_Up_Help`, `Top_Up_Problem`, `Lost_Card` (and optionally the non-duplicated `Lost_Card_Procedure`), `Support_Request`, `Create_Ticket`, `Ticket_Status`, `Confirmation`, `Cancel`, and Default Fallback.

1. General assistance: Dialogflow static intent -> predefined response.
2. Top-up help: Dialogflow static intent -> actual PayMongo-dashboard procedure -> unresolved issue -> ticket form.
3. Lost card: Dialogflow/static rule -> submit ticket -> admin reviews any freeze/replacement action.
4. Escalation: `Support_Request`/explicit confirmation -> existing ticket form; the client never supplies a passenger ID.
5. Ticket status: Dialogflow detects intent -> backend queries ticket by authenticated `passenger_id` -> actual latest status.
6. Resolution: admin resolves -> locked backend transaction marks `RESOLVED` -> one backend Brevo email; no Dialogflow, Gemini, or open chat window is involved.

## Gemini role

Gemini is a controlled fallback only after Dialogflow cannot match an unsupported query and no local predefined rule applies. It receives a short safe context and has no API access to tickets, cards, balances, account changes, or email. It is not used for ticket status, ticket creation, card freeze/replacement, top-up completion, or resolution email.

## Security and verification

- Passenger identity comes from the JWT authentication principal; no request accepts a passenger/user ID.
- Passenger ticket endpoints filter by `passenger_id` and return `not found` for another passenger's ticket, preventing IDOR disclosure.
- Support ticket creation remains authenticated and rate limited by the existing filter.
- Chat retains its per-user/IP message limit and input validation.
- Ticket-resolution locking prevents duplicate resolution emails under concurrent requests.
- `./mvnw.cmd -DskipTests compile` completed successfully on 2026-08-11.

Recommended end-to-end checks: use two passenger accounts to verify cross-account ticket reads return not found; submit a top-up issue and confirm it appears in the admin interface; resolve it twice concurrently and confirm one email; test every Dialogflow phrase above; and test unsupported/prompt-injection wording to ensure it stays in the non-action fallback.
