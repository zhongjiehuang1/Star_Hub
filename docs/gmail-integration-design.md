# Gmail integration: implementation and release status

The Gmail classes and browser component are an **unregistered prototype**, not
a deployed integration. The server must not expose the routes or accept live
Google credentials until the review and infrastructure requirements below
are met. No Google accounts or mail have been accessed
while developing this prototype.

## Intended user flow

The owner explicitly connects a Google account with OAuth authorization code
and PKCE, limited to `gmail.readonly`. The server encrypts refresh credentials
per Star Hub account and generation, never returns them to a browser or model,
and only reads or searches mail at the owner's request. Mail text goes to a
chosen external model only after the owner selects the mail and confirms the
provider disclosure. Disconnect first disables local reads and then revokes the
grant; an unconfirmed upstream revocation remains visibly pending. Logged-out or
idle-expired sessions cannot read the mailbox, but the stored connection is
intended to survive subsequent logins and restarts.

## Authorization state and remaining review

OAuth token exchange is a one-shot external side effect. A successful exchange
can finish as the Star Hub session expires, changes account, logs out, or a
concurrent disconnect starts. It can also succeed at Google while the response
is lost. Retrying the authorization code or pretending that the operation
failed safely would be incorrect. The prototype remains unregistered pending
independent review and deployment prerequisites.

The prototype records a durable account-generation-bound authorization intent
before redirect, limits it to one per account and transitions it before the
single upstream exchange. Disconnect cancels an unstarted intent; a late token
from a concurrent exchange is stored encrypted in the disabled `REVOKING`
state. Successful tokens are persisted against the originating account even if
the browser session has ended, without disclosing them to the expired session.
An uncertain database commit is retried using the same operation receipt; the
authorization code is never replayed. If Google's response is lost, the user is
asked to review/revoke the Google grant and explicitly confirm doing so.

If a process crashes while the exchange is in flight, `EXCHANGING` remains
blocked. A timeout cannot prove that an old node will not resume and issue or
finish its Google call. The UI directs the user to check Google and contact the
administrator; automatic reconnection is deliberately unavailable until safe
node isolation and manual recovery are established. Never claim the grant was
removed merely because the connection timed out.

Test concurrent tabs, start/disconnect/exchange races, session expiry and
account change at each step, dropped responses before and after database commit,
node restart/failover, repeated disconnect, and unknown revocation outcomes.
All state transitions and migrations need independent security and capacity
review, including real node restart/failover and injected database-commit
failures. Account deletion and generation changes also need a defined way to
revoke or securely retire credentials.

## Configuration and logging gates

Google Cloud configuration is external: enable Gmail API, configure the consent
screen and web OAuth client, register the exact HTTPS callback, and supply the
client credentials through private deployment configuration. Gmail read-only is
a restricted scope; external publication may require Google verification and a
security assessment. These requirements must be checked for the actual use.

The prototype reads these values from protected server properties:

```
gmail.oauth.client.id=<Google web client ID>
gmail.oauth.client.secret=<Google web client secret>
gmail.oauth.redirect.uri=https://starhub.zhongjiehuang.com/api/gmail/callback
```

The callback must exactly match the Google Cloud OAuth registration and a
configured allowed HTTPS origin. The existing protected `vault.key` is the
encryption key source. Do not place client secrets or vault keys in source
control, browser assets, chat, logs, or deployment examples.

The callback query contains sensitive authorization data. The repository nginx
template disables access/error logging for the entire Gmail prefix and rejects
HTTP; `SecurityFilter` discards this prefix when preserving region-gate return
URLs. Before activation verify the effective configuration on every ingress,
load balancer and Tomcat node, including error and access logging, alternate
path spellings, HTTP redirects, and service diagnostics. A repository template
alone is not evidence that production logging is safe. Deploy with tested
backup, rollback, migration `019`, storage capacity proof, and secret rotation.

The prototype extracts bounded `text/plain` mail bodies; HTML-only messages and
attachments are currently not summarized. This is a visible limitation, not a
claim of full Gmail support.
