---
name: suggest-prompt-improvement
description: Read reviewed conversations - transcripts, ratings and reviewer comments - and suggest a specific edit to the agent prompt that produced them. Use when asked to improve the prompt, act on review feedback, work out why conversations are rated poorly, or turn reviewer comments into a prompt change. Suggests only; never writes to the database.
---

# Suggesting a prompt improvement

Admins rate conversations out of five at `/admin/conversations` and write a line about what
should have gone better. This turns that feedback into a concrete, defensible edit to one of the
three prompts.

## The one hard rule

**Never change a prompt. Only propose one.**

Do not `POST /admin/prompts/...`, do not `insert into prompt` or `update prompt`, and do not edit
`app/src/main/resources/knowledge/*.md` — those are only the seed for a database that has moved
on. A person decides whether a prompt changes, by pasting your suggestion into
`/admin/prompts/{kind}` themselves. Say so at the end of every suggestion.

Writing the proposal to a scratch file so it can be read or pasted is fine. Applying it is not.

## Gathering the evidence

`gather.sh` in this directory reads everything you need straight from Postgres. It needs only
`docker compose up -d`; the application does not have to be running.

```bash
.claude/skills/suggest-prompt-improvement/gather.sh reviews --max-rating 3   # worst first, JSON
.claude/skills/suggest-prompt-improvement/gather.sh conversation <uuid>      # one, in full
.claude/skills/suggest-prompt-improvement/gather.sh prompts                  # every version
.claude/skills/suggest-prompt-improvement/gather.sh prompt BRIEF             # the live body
.claude/skills/suggest-prompt-improvement/gather.sh prompt OUTREACH 2        # a specific version
```

`reviews` returns, per conversation: the rating, the reviewer's comment, the lead's details, the
full transcript with every tool call and its arguments, and `prompts_used` — the exact versions
that conversation ran on.

Start with the low ratings. Read at least three conversations before proposing anything: one
comment is an anecdote, and a prompt edit made from an anecdote usually breaks something else.

## Working out which prompt is at fault

There are three, and the distinction matters because you are editing exactly one:

| Prompt | Owns |
| --- | --- |
| `BRIEF` | who the agent is, tone, message length, the guardrails, when to hand off |
| `FAQ` | the facts it may state — waiting periods, LHC, the surcharge, the rebate |
| `OUTREACH` | the shape of the very first message, and nothing else |

Map the complaint to the prompt that actually governs it:

- "too pushy", "didn't answer the question", "should have offered a call" → `BRIEF`
- "said something wrong about waiting periods" → `FAQ`
- "the opening was confusing", "didn't say who it was" → `OUTREACH`

Two things are **not** prompt problems, and proposing prompt text for them is wrong:

- **A wrong price or a wrong tier.** Those come from `find_policies` and `check_cover`, which
  read `knowledge/products.json`. Look at the tool call and its result in the transcript. If the
  tool returned it, the catalogue is wrong, not the prompt — say so.
- **A message arriving in several parts, or an odd character count.** That is `sms.max-parts` and
  the GSM-7/UCS-2 segmenting, not instructions.

Check `prompts_used` before you write anything. If the version a conversation ran on is no longer
current, the complaint may already be fixed — read the current body before proposing an edit to
it. If `prompts_used` is empty the conversation predates pinning; say that its evidence is
weaker, because you cannot know exactly what it was told.

## What to propose

Read the current body of the prompt you are editing. Then make **the smallest change that
addresses the pattern**. A rewrite is almost never the right answer: the brief is load-bearing,
and its guardrails were each written for a reason.

Prefer adding or amending one rule under the heading where it belongs. Match the voice of the
surrounding prose — the prompts are written as instructions to a person, in plain sentences, with
the reason attached where the reason is not obvious.

Before proposing, check the rule is not already there and being ignored. If it is, say so: the
fix is to make the existing rule clearer or more specific, not to add a second one that says the
same thing. Two rules that overlap is how a brief becomes noise.

## The output

Give the person these five things, in this order:

1. **What the evidence shows** — the pattern across the conversations you read, with the ratings
   and comments that support it. Quote the agent's actual words.
2. **Which prompt, and which version** you are proposing to change.
3. **The change**, as a diff or as the specific lines to add or replace, with enough surrounding
   context to place it.
4. **Why it should work**, tied to a specific line in a specific transcript.
5. **What it might cost** — the guardrail it could weaken, or the case it could make worse. Every
   prompt edit trades something; say what.

Then tell them how to apply it: open `/admin/prompts/{kind}`, click the current version, paste,
and save — which creates a new current version and leaves every earlier one readable.

If the evidence does not support a change, say that instead. "Three poor ratings, three unrelated
causes, no prompt edit would have helped" is a genuine and useful answer.
