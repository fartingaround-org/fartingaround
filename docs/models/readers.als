/*
 * readers.als — first-draft Alloy 6 model for specs/comments-and-accounts-plan.md
 * Drafted 2026-11-11. First draft run 2026-11-11 in Alloy 6.2.0: 7 checks
 * passed, memberOnTwoDevices found an instance. Second draft (unban, remove
 * contact, add-device links) run the same day: 11 checks passed, 3 runs
 * found instances. Third change (owner links always wipe) NOT yet run.
 *
 * In scope: contacts (role chosen by the owner), sign-in links (invite and
 * recovery are the same mechanism), passkeys, sessions, reader tokens,
 * story visibility, ban / unban / remove, role change, add-device links.
 * Out of scope: self sign-up / pending, comments (plain requirements in the
 * spec, including hide-on-ban), media and feed caching.
 */
module readers

abstract sig Role {}
one sig Member, Commenter extends Role {}

abstract sig Status {}
one sig Invited, Active, Banned, Removed extends Status {}

fun Gone: set Status { Banned + Removed }

abstract sig Visibility {}
one sig Public, Private extends Visibility {}

sig Contact {
  var role: one Role,
  var status: one Status
}

-- A link atom is in none of the three sets until the owner mints it.
sig Link { owner: one Contact }
-- Short-lived link a signed-in reader makes for their own second device.
sig DeviceLink extends Link {}
var sig Unused, Used, Dead in Link {}      -- Dead = expired or revoked

sig Passkey { holder: one Contact }
var sig LivePasskey in Passkey {}

sig Session { who: one Contact }
var sig LiveSession in Session {}

sig Token { bearer: one Contact }
var sig LiveToken in Token {}

sig Story { var vis: one Visibility }
var sig Published in Story {}

---------------------------------------------------------------- access

-- Evaluated per request from current role/status; never baked into a
-- session or token. That is what makes demotion and visibility flips safe.
pred canRead[c: Contact, s: Story] {
  s in Published
  s.vis = Public or (c.status = Active and c.role = Member)
}

pred anonCanRead[s: Story] { s in Published and s.vis = Public }

pred canComment[c: Contact, s: Story] {
  c.status = Active
  canRead[c, s]
}

---------------------------------------------------------------- frames

pred sameLinks    { Unused' = Unused and Used' = Used and Dead' = Dead }
pred sameCreds    { LiveSession' = LiveSession and LivePasskey' = LivePasskey and LiveToken' = LiveToken }
pred sameContacts { role' = role and status' = status }
pred sameStories  { vis' = vis and Published' = Published }

---------------------------------------------------------------- events

pred init {
  no (Unused + Used + Dead)
  no (LiveSession + LivePasskey + LiveToken)
  all c: Contact | c.status = Invited
}

-- Owner creates a sign-in link for a contact. Any older unused link for the
-- same contact is revoked: at most one outstanding link per person.
pred mint[l: Link] {
  l not in DeviceLink
  l not in (Unused + Used + Dead)
  l.owner.status not in Gone
  let old = Unused & owner.(l.owner) {
    Unused' = (Unused - old) + l
    Dead' = Dead + old
  }
  Used' = Used
  sameCreds and sameContacts and sameStories
}

pred expire[l: Link] {
  l in Unused
  Unused' = Unused - l
  Dead' = Dead + l
  Used' = Used
  sameCreds and sameContacts and sameStories
}

-- A signed-in, active reader creates an add-device link for themselves.
-- It does not revoke anything.
pred mintDevice[l: Link] {
  l in DeviceLink
  l not in (Unused + Used + Dead)
  l.owner.status = Active
  some LiveSession & who.(l.owner)
  Unused' = Unused + l
  Used' = Used
  Dead' = Dead
  sameCreds and sameContacts and sameStories
}

-- Shared part of redeeming a link: reader opens it and presses Continue.
pred redeemCore[l: Link, s: Session] {
  l in Unused
  l.owner.status not in Gone
  s.who = l.owner
  s not in LiveSession
  Used' = Used + l
  status' = status ++ l.owner -> Active
  role' = role
  sameStories
}

-- Add-device links only: adds a session for an already active reader and
-- leaves everything else in place.
pred redeemKeep[l: Link, s: Session] {
  redeemCore[l, s]
  l in DeviceLink
  l.owner.status = Active
  Unused' = Unused - l
  Dead' = Dead
  LiveSession' = LiveSession + s
  LivePasskey' = LivePasskey
  LiveToken' = LiveToken
}

-- Every owner-issued link (invite or recovery): everything the contact held
-- before is revoked. Decided 2026-11-11: there is no "keep existing" option.
pred redeemWipe[l: Link, s: Session] {
  redeemCore[l, s]
  l not in DeviceLink
  -- also kills any add-device link made from a session we are about to revoke
  Unused' = Unused - owner.(l.owner)
  Dead' = Dead + ((Unused & owner.(l.owner)) - l)
  LiveSession' = (LiveSession - who.(l.owner)) + s
  LivePasskey' = LivePasskey - holder.(l.owner)
  LiveToken' = LiveToken - bearer.(l.owner)
}

pred addPasskey[p: Passkey] {
  p not in LivePasskey
  some LiveSession & who.(p.holder)
  LivePasskey' = LivePasskey + p
  LiveSession' = LiveSession
  LiveToken' = LiveToken
  sameLinks and sameContacts and sameStories
}

pred passkeySignIn[p: Passkey, s: Session] {
  p in LivePasskey
  p.holder.status = Active
  s.who = p.holder
  s not in LiveSession
  LiveSession' = LiveSession + s
  LivePasskey' = LivePasskey
  LiveToken' = LiveToken
  sameLinks and sameContacts and sameStories
}

-- Feed / app token, issued from a signed-in session.
pred issueToken[t: Token] {
  t not in LiveToken
  some LiveSession & who.(t.bearer)
  LiveToken' = LiveToken + t
  LiveSession' = LiveSession
  LivePasskey' = LivePasskey
  sameLinks and sameContacts and sameStories
}

pred signOut[s: Session] {
  s in LiveSession
  LiveSession' = LiveSession - s
  LivePasskey' = LivePasskey
  LiveToken' = LiveToken
  sameLinks and sameContacts and sameStories
}

-- Ban and remove do the same thing to credentials; only the end status differs.
pred revoke[c: Contact, st: Status] {
  c.status != Removed
  status' = status ++ c -> st
  role' = role
  LiveSession' = LiveSession - who.c
  LivePasskey' = LivePasskey - holder.c
  LiveToken' = LiveToken - bearer.c
  Unused' = Unused - owner.c
  Dead' = Dead + (Unused & owner.c)
  Used' = Used
  sameStories
}

pred ban[c: Contact]    { revoke[c, Banned] }
pred remove[c: Contact] { revoke[c, Removed] }

-- Unban only lifts the block. Nothing destroyed by the ban comes back; the
-- owner must mint a new link.
pred unban[c: Contact] {
  c.status = Banned
  status' = status ++ c -> Invited
  role' = role
  sameLinks and sameCreds and sameStories
}

pred setRole[c: Contact, r: Role] {
  role' = role ++ c -> r
  status' = status
  sameLinks and sameCreds and sameStories
}

pred setVis[s: Story, v: Visibility] {
  vis' = vis ++ s -> v
  Published' = Published
  sameLinks and sameCreds and sameContacts
}

pred publish[s: Story]   { Published' = Published + s and vis' = vis and sameLinks and sameCreds and sameContacts }
pred unpublish[s: Story] { Published' = Published - s and vis' = vis and sameLinks and sameCreds and sameContacts }

pred stutter { sameLinks and sameCreds and sameContacts and sameStories }

fact traces {
  init
  always (
    stutter
    or (some l: Link | mint[l] or mintDevice[l] or expire[l])
    or (some l: Link, s: Session | redeemKeep[l, s] or redeemWipe[l, s])
    or (some p: Passkey | addPasskey[p])
    or (some p: Passkey, s: Session | passkeySignIn[p, s])
    or (some t: Token | issueToken[t])
    or (some s: Session | signOut[s])
    or (some c: Contact | ban[c] or remove[c] or unban[c])
    or (some c: Contact, r: Role | setRole[c, r])
    or (some s: Story, v: Visibility | setVis[s, v])
    or (some s: Story | publish[s] or unpublish[s])
  )
}

---------------------------------------------------------------- properties

-- Spec invariant 5: a banned or removed contact holds nothing.
assert BannedHoldsNothing {
  always all c: Contact | c.status in Gone implies
    (no (LiveSession & who.c) and no (LivePasskey & holder.c)
     and no (LiveToken & bearer.c) and no (Unused & owner.c))
}

-- Spec invariant 3: a link is usable at most once.
assert LinkUsedOnce {
  always all l: Link | l in Used implies always l not in Unused
}

-- Nobody holds a credential without having redeemed a link of their own.
assert CredentialsComeFromLinks {
  always all c: Contact |
    some ((LiveSession & who.c) + (LivePasskey & holder.c) + (LiveToken & bearer.c))
      implies some (Used & owner.c)
}

-- At most one outstanding owner-issued link per contact (a leaked old
-- invite cannot be used after the owner issues a replacement).
assert OneOutstandingLink {
  always all c: Contact | lone ((Unused - DeviceLink) & owner.c)
}

-- A new owner-issued link also kills any pending add-device links.
assert NewLinkKillsDeviceLinks {
  always all l: Link | mint[l] implies after (Unused & owner.(l.owner)) = l
}

-- An add-device link is only ever live for an active contact.
assert DeviceLinkOnlyForActive {
  always all l: DeviceLink | l in Unused implies l.owner.status = Active
}

-- After an unban the contact has no way in until the owner mints a link.
assert UnbanComesBackEmpty {
  always all c: Contact | unban[c] implies after {
    c.status = Invited
    no (LiveSession & who.c)
    no (LivePasskey & holder.c)
    no (LiveToken & bearer.c)
    no (Unused & owner.c)
  }
}

-- An owner-issued link can never be redeemed without wiping.
assert OwnerLinksAlwaysWipe {
  always all l: Link - DeviceLink, s: Session | not redeemKeep[l, s]
}

-- Removal is final.
assert RemovedIsFinal {
  always all c: Contact | c.status = Removed implies always c.status = Removed
}

-- Spec invariant 7: redeeming an owner-issued link leaves exactly the new
-- session and no passkeys, tokens or other links.
assert WipeRecoveryIsClean {
  always all l: Link, s: Session | redeemWipe[l, s] implies after {
    (LiveSession & who.(l.owner)) = s
    no (LivePasskey & holder.(l.owner))
    no (LiveToken & bearer.(l.owner))
    no (Unused & owner.(l.owner))
  }
}

-- Spec invariants 1 and 4: only an active member reads a private story,
-- whatever credentials they still hold; commenters and anonymous never do.
assert PrivateOnlyForActiveMembers {
  always all c: Contact, s: Story |
    (canRead[c, s] and s.vis = Private) implies (c.role = Member and c.status = Active)
  always all s: Story | anonCanRead[s] implies s.vis = Public
}

-- Spec invariant 8: commenting never exceeds reading.
assert CommentImpliesRead {
  always all c: Contact, s: Story | canComment[c, s] implies canRead[c, s]
}

check BannedHoldsNothing          for 4 but 10 steps
check LinkUsedOnce                for 4 but 10 steps
check CredentialsComeFromLinks    for 4 but 10 steps
check OneOutstandingLink          for 4 but 10 steps
check NewLinkKillsDeviceLinks     for 4 but 10 steps
check DeviceLinkOnlyForActive     for 4 but 10 steps
check UnbanComesBackEmpty         for 4 but 10 steps
check RemovedIsFinal              for 4 but 10 steps
check OwnerLinksAlwaysWipe        for 4 but 10 steps
check WipeRecoveryIsClean         for 4 but 10 steps
check PrivateOnlyForActiveMembers for 4 but 10 steps
check CommentImpliesRead          for 4 but 10 steps

-- Sanity: a member joins, adds a second passkey, and reads a private story.
run memberOnTwoDevices {
  some c: Contact, s: Story | eventually {
    c.role = Member
    #(LivePasskey & holder.c) = 2
    s.vis = Private
    canRead[c, s]
  }
} for 4 but 10 steps

-- Sanity: a second device joins through an add-device link.
run secondDeviceViaLink {
  some l: DeviceLink | eventually (l in Used and #LiveSession = 2)
} for 4 but 10 steps

-- Sanity: banned, unbanned, then back in with a new link.
run bannedThenBack {
  some c: Contact | eventually (c.status = Banned and eventually (c.status = Active and some LiveSession & who.c))
} for 4 but 10 steps

/*
 * Things to poke at together:
 * 1. Decided 2026-11-11: unban exists and the contact comes back with no
 *    credentials; hidden comments stay hidden until the owner shows them.
 * 2. Decided 2026-11-11: owner-issued links always wipe. redeemKeep is now
 *    only the add-device path.
 * 3. A contact with status Invited and role Member cannot read private
 *    stories until they redeem. Good. Remove behaves like ban and is final.
 * 4. PrivateOnlyForActiveMembers is true by construction because access is
 *    computed per request. The real hazard is outside the model: edge
 *    caches, feed readers and the app holding copies. Worth a second model
 *    with a Cache sig once the media/feeds design is settled.
 */
