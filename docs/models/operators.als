/*
 * operators.als — first-draft Alloy 6 model for the server-admin side of
 * Projects/docs/fartingaround/hosting-and-admin-plan.md (Operator auth and
 * Co-operators). Drafted 2026-11-20, lockdown added the same day. First run
 * 2026-11-20 (Tony): all 12 checks passed with no counterexample and all 6
 * runs found an instance, at scope 4 and 10 steps (12 for the lockdown run).
 * Second change the same day (the target contests instead of cancelling;
 * new check TargetCannotQuietlyCancel, new run requesterWithdraws) run
 * 2026-11-20 (Tony): 13 checks passed, 7 runs found an instance.
 *
 * In scope: owners of one server, their paired devices, pairing codes
 * (invites from an owner, add-device codes, and codes printed over SSH),
 * removing an owner with the 72-hour delay, cancelling a removal, leaving,
 * revoking a device, lockdown, and what SSH can always do.
 * Out of scope: the limited operator role (deferred), sessions and the
 * challenge/response itself, site owners (readers.als covers a site's own
 * people), who actually holds SSH access, real clock time. The 72 hours is
 * the gap between requestRemoval and completeRemoval; an invite expiring is
 * the expire event.
 */
module operators

sig Person {
  -- A removal waiting out its 72 hours: the owner who asked for it.
  var removedBy: lone Person
}
var sig Owner in Person {}

sig Device { holder: one Person }
var sig Paired, Revoked in Device {}        -- neither = never paired

-- A one-time pairing code. No issuer = printed over SSH (this is also the
-- setup secret that cloud-init plants for the very first device).
sig Invite {
  to: one Person,
  issuer: lone Device
}
var sig Open, Spent, Dead in Invite {}      -- Dead = expired or revoked

-- Lockdown: the server refuses every owner action from the app until
-- someone with SSH lifts it. Locked is empty or the one Server atom.
one sig Server {}
var sig Locked in Server {}

fun pendingRemoval: set Person { removedBy.Person }

---------------------------------------------------------------- frames

pred sameOwners   { Owner' = Owner }
pred sameDevices  { Paired' = Paired and Revoked' = Revoked }
pred sameInvites  { Open' = Open and Spent' = Spent and Dead' = Dead }
pred sameRemovals { removedBy' = removedBy }
pred sameLock     { Locked' = Locked }

---------------------------------------------------------------- events

-- A fresh server: nobody is an owner until the setup code is redeemed.
pred init {
  no Owner
  no (Paired + Revoked)
  no (Open + Spent + Dead)
  no removedBy
  no Locked
}

-- Anyone with SSH prints a pairing code. Always possible: SSH is the root
-- of trust, for setup and for "every owner lost their phone".
pred sshMint[i: Invite] {
  sameLock
  no i.issuer
  i not in (Open + Spent + Dead)
  Open' = Open + i
  Spent' = Spent
  Dead' = Dead
  sameOwners and sameDevices and sameRemovals
}

-- An owner, from a paired device, makes a code: for a new co-owner, or for
-- another device of their own. Not for a different existing owner.
pred invite[d: Device, i: Invite] {
  no Locked
  sameLock
  d in Paired
  d.holder in Owner
  i.issuer = d
  i not in (Open + Spent + Dead)
  i.to = d.holder or i.to not in Owner
  Open' = Open + i
  Spent' = Spent
  Dead' = Dead
  sameOwners and sameDevices and sameRemovals
}

pred expire[i: Invite] {
  sameLock
  i in Open
  Open' = Open - i
  Dead' = Dead + i
  Spent' = Spent
  sameOwners and sameDevices and sameRemovals
}

-- Redeeming a code an owner issued: the new device is paired, its holder is
-- an owner, and nothing else changes.
pred redeemKeep[i: Invite, d: Device] {
  no Locked
  sameLock
  i in Open
  some i.issuer
  d.holder = i.to
  d not in (Paired + Revoked)
  Open' = Open - i
  Spent' = Spent + i
  Dead' = Dead
  Paired' = Paired + d
  Revoked' = Revoked
  Owner' = Owner + i.to
  sameRemovals
}

-- Redeeming an SSH code is recovery: the person's older devices are revoked
-- (the lost phone may be in someone else's hands), codes those devices made
-- die, and removals that person had asked for are dropped.
pred redeemWipe[i: Invite, d: Device] {
  sameLock
  i in Open
  no i.issuer
  d.holder = i.to
  d not in (Paired + Revoked)
  let old = Paired & holder.(i.to) {
    Paired' = (Paired - old) + d
    Revoked' = Revoked + old
    Open' = Open - i - issuer.old
    Dead' = Dead + (Open & issuer.old)
  }
  Spent' = Spent + i
  Owner' = Owner + i.to
  removedBy' = removedBy - (Person -> i.to)
}

-- An owner signs one of their own devices out (possibly the one in hand).
-- Refused when it is the last paired device on the server.
pred revokeDevice[mine, d: Device] {
  no Locked
  sameLock
  mine in Paired and d in Paired
  mine.holder = d.holder
  some Paired - d
  Paired' = Paired - d
  Revoked' = Revoked + d
  Open' = Open - issuer.d
  Dead' = Dead + (Open & issuer.d)
  Spent' = Spent
  sameOwners and sameRemovals
}

-- What happens to everything a person held when they stop being an owner.
pred evict[p: Person] {
  Owner' = Owner - p
  Paired' = Paired - holder.p
  Revoked' = Revoked + (Paired & holder.p)
  let dead = Open & (to.p + issuer.(holder.p)) {
    Open' = Open - dead
    Dead' = Dead + dead
  }
  Spent' = Spent
  -- the request against them is done, and requests they made go with them
  removedBy' = removedBy - (p -> Person) - (Person -> p)
}

-- One owner asks to remove another. Nothing happens yet.
pred requestRemoval[d: Device, p: Person] {
  no Locked
  sameLock
  d in Paired
  d.holder in Owner
  p in Owner
  p != d.holder
  no p.removedBy
  removedBy' = removedBy + (p -> d.holder)
  sameOwners and sameDevices and sameInvites
}

-- The owner who asked for a removal changes their mind. Only they can call
-- it off quietly (decided 2026-11-20).
pred withdrawRemoval[d: Device, p: Person] {
  no Locked
  sameLock
  d in Paired
  d.holder = p.removedBy
  removedBy' = removedBy - (p -> Person)
  sameOwners and sameDevices and sameInvites
}

-- The target objects. They cannot cancel it themselves: contesting puts the
-- server into lockdown, and SSH decides (decided 2026-11-20).
pred contestRemoval[d: Device, p: Person] {
  d in Paired
  d.holder = p
  some p.removedBy
  freeze
}

-- 72 hours later, uncancelled. Refused if it would leave no paired device.
pred completeRemoval[p: Person] {
  no Locked
  sameLock
  some p.removedBy
  p.removedBy in Owner
  some Paired - holder.p
  evict[p]
}

-- An owner steps down, at once. Refused if nobody else could then act.
pred leave[d: Device] {
  no Locked
  sameLock
  d in Paired
  d.holder in Owner
  some Paired - holder.(d.holder)
  evict[d.holder]
}

-- Anyone with SSH can remove an owner outright. No guard: SSH overrides.
pred sshEvict[p: Person] {
  sameLock
  p in Owner
  evict[p]
}

-- The nuclear option. Any owner, from a paired device, freezes the app side
-- of the server. Codes made in the app die and pending removals are dropped:
-- from here on SSH decides who the owners are.
pred lockdown[d: Device] {
  d in Paired
  d.holder in Owner
  freeze
}

-- What going into lockdown does, however it was triggered.
pred freeze {
  no Locked
  Locked' = Server
  let dead = Open & issuer.Device {
    Open' = Open - dead
    Dead' = Dead + dead
  }
  Spent' = Spent
  no removedBy'
  sameOwners and sameDevices
}

-- Only SSH lifts it, after whoever holds SSH has put the owners right
-- (sshEvict, sshMint + redeemWipe).
pred sshUnlock {
  some Locked
  no Locked'
  sameOwners and sameDevices and sameInvites and sameRemovals
}

pred stutter { sameOwners and sameDevices and sameInvites and sameRemovals and sameLock }

fact traces {
  init
  always (
    stutter
    or (some i: Invite | sshMint[i] or expire[i])
    or (some d: Device, i: Invite | invite[d, i])
    or (some i: Invite, d: Device | redeemKeep[i, d] or redeemWipe[i, d])
    or (some mine, d: Device | revokeDevice[mine, d])
    or (some d: Device, p: Person | requestRemoval[d, p] or withdrawRemoval[d, p] or contestRemoval[d, p])
    or (some p: Person | completeRemoval[p] or sshEvict[p])
    or (some d: Device | leave[d] or lockdown[d])
    or sshUnlock
  )
}

---------------------------------------------------------------- properties

pred usedSsh { some p: Person | sshEvict[p] }

pred sshActed {
  (some i: Invite | sshMint[i]) or (some i: Invite, d: Device | redeemWipe[i, d]) or usedSsh
}

-- Lockdown holds: while locked, nothing about owners, devices or removals
-- changes and no new code appears, unless SSH did it.
assert LockdownFreezesApp {
  always ((some Locked and not sshActed) implies
    (Owner' = Owner and Paired' = Paired and Revoked' = Revoked and removedBy' = removedBy and Open' in Open))
}

-- Lockdown is only ever lifted over SSH, and it starts clean.
assert LockdownStartsClean {
  always all d: Device | lockdown[d] implies after (no removedBy and no (Open & issuer.Device))
  always all d: Device, p: Person | contestRemoval[d, p] implies after (some Locked and no removedBy and no (Open & issuer.Device))
}

-- A contested removal never completes on its own, and the target never
-- makes a request against them vanish without the server locking: a pending
-- removal ends only by completing, by its requester withdrawing or ceasing
-- to be an owner, by lockdown, or over SSH.
assert TargetCannotQuietlyCancel {
  always all p: Person | (some p.removedBy and after (no p.removedBy and no Locked and p in Owner)) implies
    ((some d: Device | withdrawRemoval[d, p])
      or (some r: Person | r = p.removedBy and after r not in Owner)
      or sshActed)
}

-- Plan invariant 1: the app never removes the last owner. Only SSH can.
assert AppNeverRemovesLastOwner {
  always ((some Owner and not usedSsh) implies after some Owner)
}

-- The app never signs out the last device either: once something is
-- paired, some device can always act, short of SSH.
assert AppNeverLocksItselfOut {
  always ((some Paired and not usedSsh) implies after some Paired)
}

-- Plan invariant 2: no owner is removed by another in a single step. An
-- owner with no request against them is still an owner in the next state
-- unless they left themselves or SSH was used.
assert NoInstantEviction {
  always all p: Owner |
    (no p.removedBy and not sshEvict[p] and not (some d: Device | d.holder = p and leave[d]))
      implies after p in Owner
}

-- A pending removal always has a living requester and a living target.
assert RemovalsAreBetweenOwners {
  always all p: pendingRemoval | p in Owner and p.removedBy in Owner and p.removedBy != p
}

-- Plan invariant 3: a pairing code works at most once and never after it
-- has expired or been revoked.
assert InviteUsedOnce {
  always all i: Invite | i in Spent implies always i in Spent
  always all i: Invite | i in Dead implies always i in Dead
  always no (Open & Spent) and always no (Open & Dead) and always no (Spent & Dead)
}

-- A code made in the app is only ever live while the device that made it
-- is still paired to an owner.
assert OpenInvitesHaveLiveIssuer {
  always all i: Open | some i.issuer implies (i.issuer in Paired and i.issuer.holder in Owner)
}

-- Every paired device belongs to an owner.
assert DevicesBelongToOwners {
  always all d: Paired | d.holder in Owner
}

-- A revoked device never comes back; its owner pairs a new one.
assert RevokedStaysOut {
  always all d: Device | d in Revoked implies always d not in Paired
}

-- Someone who stops being an owner keeps nothing: no devices, no codes
-- addressed to them, no codes they made, no removals in either direction.
assert RemovedOwnerHoldsNothing {
  always all p: Owner | (after p not in Owner) implies after {
    no (Paired & holder.p)
    no (Open & to.p)
    no (Open & issuer.(holder.p))
    no p.removedBy
    no removedBy.p
  }
}

-- Nobody becomes an owner except by redeeming a code addressed to them.
assert OwnersComeFromInvites {
  always all p: Owner | some (Spent & to.p)
}

check LockdownFreezesApp         for 4 but 10 steps
check LockdownStartsClean        for 4 but 10 steps
check TargetCannotQuietlyCancel  for 4 but 10 steps
check AppNeverRemovesLastOwner   for 4 but 10 steps
check AppNeverLocksItselfOut     for 4 but 10 steps
check NoInstantEviction          for 4 but 10 steps
check RemovalsAreBetweenOwners   for 4 but 10 steps
check InviteUsedOnce             for 4 but 10 steps
check OpenInvitesHaveLiveIssuer  for 4 but 10 steps
check DevicesBelongToOwners      for 4 but 10 steps
check RevokedStaysOut            for 4 but 10 steps
check RemovedOwnerHoldsNothing   for 4 but 10 steps
check OwnersComeFromInvites      for 4 but 10 steps

-- Sanity: setup over SSH, then a second owner joins by invite.
run twoOwners {
  eventually (#Owner = 2 and all p: Owner | some Paired & holder.p)
} for 4 but 10 steps

-- Sanity: a removal is requested and runs its course.
run removalCompletes {
  some p: Person | eventually (p in pendingRemoval and eventually (completeRemoval[p]))
} for 4 but 10 steps

-- Sanity: a removal is requested, the target contests it, the server locks.
run targetContests {
  some p: Person, d: Device | d.holder = p and eventually (p in pendingRemoval and eventually contestRemoval[d, p])
} for 4 but 10 steps

-- Sanity: a removal is requested and the requester withdraws it.
run requesterWithdraws {
  some p: Person, d: Device | eventually (p in pendingRemoval and eventually withdrawRemoval[d, p])
} for 4 but 10 steps

-- Sanity: an owner recovers over SSH and their old device is revoked.
run lostPhoneRecovery {
  some p: Person | eventually (some Revoked & holder.p and some Paired & holder.p and p in Owner)
} for 4 but 10 steps

-- The stand-off to look at together: two owners, each with a removal
-- pending against the other at the same time.
run mutualRemoval {
  some disj a, b: Person | eventually (a.removedBy = b and b.removedBy = a)
} for 4 but 10 steps

-- Sanity: the stolen-phone stand-off ends in lockdown, SSH removes the
-- compromised owner, and the server is unlocked with one owner left.
run lockdownThenSshSettlesIt {
  some p: Person | eventually (some Locked and p in Owner and eventually (no Locked and p not in Owner and some Owner))
} for 4 but 12 steps

/*
 * Things to poke at together:
 * 1. Stolen phone / rogue owner. Decided 2026-11-20: the target of a
 *    removal cannot cancel it. They can contest it, which locks the server
 *    until SSH settles who the owners are; SSH always wins. Only the
 *    requester can withdraw quietly. Any owner can also lock down directly.
 *    Outside the model and worth building: contesting and lockdown should
 *    need the fresh fingerprint/PIN check, so a thief with an unlocked
 *    phone can do neither and the removal simply completes after 72 hours.
 *    Still true: lockdown only helps where someone legitimate has SSH.
 * 2. Mutual removal. Both requests can be pending at once (run
 *    mutualRemoval). Whichever completes first wins and the other request
 *    dies with its requester. Acceptable?
 * 3. An owner under a pending removal can still invite new owners and make
 *    add-device codes during the 72 hours. Should a pending removal freeze
 *    what the target can do?
 * 4. leave is instant, so a thief on A's phone can make A step down at
 *    once (B is unharmed and can re-invite A). Fine, or should stepping
 *    down also wait?
 * 5. SSH codes always wipe the person's older devices (redeemWipe), the
 *    same rule as owner-issued links in readers.als. The in-app add-device
 *    code never does.
 * 6. Owners with no paired device can exist (they signed out their only
 *    device while another owner still had one). They count as owners for
 *    the last-owner rule but cannot act; the guards on leave and
 *    completeRemoval look at paired devices for that reason.
 * 7. Limited operators: when that role comes back, it needs a Role sig and
 *    a decision on whether a limited operator can cancel a removal.
 */
