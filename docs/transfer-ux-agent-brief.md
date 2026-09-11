# Transfer UX: show intent immediately (coding-agent brief)

Repo: `/home/carlo/Projects/klardrop`
Owner: Carlo. Implement in this checkout. Do **not** push. Do **not** rewrite transport/crypto. Leave a dirty tree he can review.

This is a **UX** job. The transfer already works. The app goes silent between tap-send and the peer coming online, so it feels dead.

## Bug (real session)

Carlo sent **text** from **mobile chat** to **desktop**. Desktop was temporarily offline / still connecting.

- Mobile showed **nothing**. No bubble. No sending state. No connecting hint.
- Seconds later both sides connected and the text arrived in milliseconds.
- So the work happened. The UI did not admit it was happening.

Same class of hole on **receive**: file send on mobile shows a bottom sheet waiting for delivery. Incoming / connecting / “someone is trying to send you something” is weak or missing, especially for text and for the first connection.

## Product rule

The moment the user **intends** to send (composer send, share sheet, paste), the sending device must show that the app is on it.

Grandma-friendly. Three states, not a protocol debugger:

1. **Connecting** — no live link yet (peer offline, dialing, handshake).
2. **Sending** — link is up, payload in flight (text or file).
3. **Sent** / **Failed** — terminal. Sent is quiet (current chat already hides SENT). Failed is obvious and retryable.

Hide advanced stuff (bytes, handshake, UKEY2, retry counts) unless we already show them on the file progress strip. Do not invent a toast for every internal step.

Receiver: if a peer is connecting or a transfer is waiting on this device, say so. Do not wait until the file row exists. Text should not be invisible until the write completes.

## What the code already does (do not fight this)

Read these before writing:

- `common/.../communication/Messenger.kt` `runSend` — inserts outgoing **TEXT** as `SendStatus.SENDING` **before** the visibility check / socket write, then flips SENT/FAILED once. `TextMessageHandler.handleOutgoing` must **not** persist (F12/F13, `docs/connection-review.md`).
- `presentation/.../chat/DeviceChatViewModel.kt`
  - `sendTextMessage` — no optimistic UI. Only `messenger.send(...).untilCompleted().lastOrNull()`. No Connecting banner.
  - `sendFiles` — **does** set `transferring(..., "Preparing to send…")` immediately, then Connecting / Waiting for accept from `MessengerSendProgress`.
- `compose-ui/.../chat/DeviceChatScreen.kt` — maps `DeliveryStatus.SENDING` → sending clock on the bubble. Composer stays enabled offline (`SENDING→FAILED`).
- Incoming: `DeviceChatViewModel` **ignores** `ReceiveMessageStatus.Started` on purpose so text receive does not pin a file-progress strip. That choice is why incoming text/connection feels like nothing.

If SENDING is already inserted in `Messenger` but the bubble still does not appear until connect, the bug is **observation / timing / which dispatcher**, not “we forgot persistence.” Fix the UI path. Do **not** insert a second row from the ViewModel (duplicate bubbles).

## Likely gaps to verify with a failing test first

1. **Text send has no immediate chrome.** Files get a strip; text relies on the DB flow. `messages` is `WhileSubscribed(5000)` from `getMessagesForDevice`. If `insertMessage` is late (queued behind dial) or the chat list does not recompose, the user sees a blank composer after send.
2. **`sendTextMessage` does not collect `Pending`.** Files map `MessengerSendProgress.Pending` → `"Connecting…"`. Text throws those emissions away (`.lastOrNull()`).
3. **Receive `Started` is swallowed.** Connecting inbound looks idle until Progress/Completed.
4. Confirm whether `Messenger.send`’s `messengerScope.launch` can stall **before** the SENDING insert (mutex / previous send waiting on connect). If insert is not actually first, move it so it cannot wait on the socket.

## Implement

Keep KMP. Fix once in `common` / `presentation` / `compose-ui` so Android, iOS, desktop all get it.

Sender (text **and** files, chat + share sheet if it has the same hole):

- On send tap: bubble or strip appears **immediately** (SENDING / Connecting). Composer can clear.
- While dialing: **Connecting**. Not a frozen empty chat.
- While writing: **Sending** (files can keep the existing bar).
- On success: bubble looks normal. On fail: Failed + retry if we already have that for files.

Receiver:

- Incoming connection / pending transfer: a short, human line or sheet. Not a blank chat.
- Do not flash a file progress bar for a one-line text forever (that’s why `Started` was ignored). A transient “Receiving…” / incoming bubble is fine.

Tests:

- Text send while peer offline/unpooled: SENDING row (or equivalent UI state) exists **before** `connectTo` succeeds.
- Chat list / `DeliveryStatus.SENDING` visible in that window.
- No duplicate text rows on retry (F12/F13).
- File path still shows Preparing/Connecting/progress.
- Incoming Started/PendingAuthorization surfaces in chat, not only discovery.

Compile the modules you touch. Do not run a full multiplatform assemble unless cheap.

## Out of scope

- LocalSend interop, HTTPS, README crypto, Omarchy QML, shrinking the Linux tarball.
- New protocol states on the wire.
- Push / commit / PR unless the tree was already on a feature branch you created locally. Prefer uncommitted work on current branch.
