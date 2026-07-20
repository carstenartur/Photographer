# D10 K-Transition / Decoder-Outcome Matrix

## Hypothesis ID

D10 — determine whether one HYBRID-versus-D8 K-transition class consistently predicts a specific D8 quality or completion failure across the fixed lossless CR3 panel.

## Goal

D9 found three different first non-zero K decisions and therefore no common semantic cause. D10 changes no decoder behavior. It counts transition disagreements before the first bit/symbol split of every tile-plane and correlates them with the already durable, corrected D8 outcomes.

## Fixed evidence

The matrix uses exactly the eleven D8 lossless fixtures and the corrected D8 outcome table in:

```text
docs/cr3-results/2026-07-20-D8-zero-contracted-k-outcomes.tsv
```

D8 metrics are not recomputed under new semantics. The table binds each fixture to its existing:

- global MAE delta;
- optical-black MAE delta;
- tile-boundary delta;
- completion delta;
- exhaustion state;
- outcome class.

HYBRID and D8 are observed through the shared D9 parser over a fixed prefix of 16,384 symbols per tile-plane. A transition is counted only while both paths have identical:

- pixel coordinate and symbol ordinal;
- byte:bit start and end;
- K before reading;
- quotient, remainder and unsigned symbol;
- signed residual, predictor and reconstructed value.

The plane stops contributing comparable transitions at its first split.

## Transition classes

Zero and non-zero symbols are counted separately for:

- both hold;
- both increase;
- both decrease;
- HYBRID decrease / D8 hold;
- HYBRID decrease / D8 increase;
- HYBRID hold / D8 increase;
- HYBRID increase / D8 hold;
- HYBRID increase / D8 decrease;
- other opposite- or same-direction decisions.

K-before and symbol-magnitude bins are also retained.

## Predeclared predictive gate

A transition class may select a semantic follow-up only when:

1. it occurs on non-zero symbols in at least two fixtures of the same failure outcome class; and
2. it occurs in no `COMPLETE_IMPROVEMENT` fixture.

Zero-only evidence, one-fixture support or any complete-improvement contradiction forces rejection.

## Execution evidence

- implementation commit: `d99ab8f02ef9a919267bff576391db1db1c9d467`;
- GitHub Actions run: `29772871244`;
- artifact: `cr3-research-evidence`;
- artifact digest:

```text
sha256:d0fe6ff97a240756f33bf7fc177f842aa5e2e00de2176826593a5efb560a644d
```

A later PR-only serialization hardening preserves deterministic K/symbol-bin ordering and does not alter transition counting, associations or the verdict.

## Verdict: **NO_PREDICTIVE_TRANSITION_CLASS**

No candidate transition passes the gate.

For each of these non-zero classes:

- `HYBRID_DECREASE_D8_HOLD`;
- `HYBRID_DECREASE_D8_INCREASE`;
- `HYBRID_HOLD_D8_INCREASE`;
- `HYBRID_INCREASE_D8_HOLD`;
- `HYBRID_INCREASE_D8_DECREASE`;

D10 finds:

- support in all three `COMPLETION_REGRESSION` fixtures;
- support in both `COMPLETE_GLOBAL_REGRESSION` fixtures;
- contradictions in three `COMPLETE_IMPROVEMENT` fixtures.

`OTHER_OPPOSITE_DIRECTION` has no supporting fixture and therefore cannot be predictive.

All eleven fixtures reach a first split in each of their eight observed lossless tile-plane streams. The number of aligned symbols before those splits varies substantially by fixture, but mere presence of any disagreement class does not separate improved and regressed outcomes.

## Interpretation

The transition disagreements are ubiquitous rather than failure-specific. A binary rule such as “replace every HYBRID decrease/D8 increase” would also alter fixtures where D8 improves globally, in optical-black regions and at boundaries.

D10 therefore rejects using transition-class presence alone as a decoder rule. This does not prove either HYBRID or D8 correct. It shows that the current empirical candidate space is underspecified: the same local decision category has different downstream effects depending on context not represented by the simple class label.

## Production decision

No production decoder change. No D11 K-formula experiment is authorized by D10.

## Next step

Stop broad empirical K-rule mutation. The strongest remaining clean-room source gap is explicit in the current specification: the exact Canon-patent definition of `Kmin(Sn)`, `Kmax(Sn)`, update step and symbol estimate has not been transcribed verbatim and remains represented by a hypothetical `±1` bracket.

The next task should therefore be source-analysis only:

1. obtain the public primary patent text and figures;
2. register the exact paragraphs/figures and document hashes or stable citations;
3. transcribe the K-update functions without consulting third-party decoder source;
4. separate patent-stated behavior from CRX-specific inference;
5. only then predeclare one exact normative candidate against the fixed panel.

Until that source gap is closed, further threshold or bracket sweeps would be weakly motivated and should not enter production.
