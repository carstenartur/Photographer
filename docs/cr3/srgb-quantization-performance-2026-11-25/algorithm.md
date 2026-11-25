# Exact sRGB16 candidate selection

The change replaces the candidate-code calculation inside `Srgb16Encoder`.
The reference remains `Math.round(65535 * SrgbConverter.linearToSrgb(linear))`,
with the existing clipping and special-value behavior. The complete input double
is retained for the final boundary comparison. A seed lookup does not round the
input to the lookup grid.

## Lookup domain and storage

Negative values, signed zero and NaN return zero. Values at or above one return
65535. Positive inputs up to and including `0.0031308` use the original reference
expression. The remaining domain is strictly inside `[2^-9, 1)`.

For a positive finite double, the nonnegative raw-bit representation is ordered
in the same way as the numerical value. Subtracting the representation of `2^-9`
and shifting right by 37 gives an index over nine exponent ranges, each with
32,768 leading-mantissa buckets. The largest possible index is 294,911 because
`bits(1) - bits(2^-9) = 9 * 2^52` and one is excluded before indexing.

| Shared array | Elements | Payload |
| --- | ---: | ---: |
| Unsigned seeds stored as `short` | 294,912 | 589,824 bytes |
| Raw-bit output boundaries stored as `long` | 65,537 | 524,296 bytes |
| Total | | 1,114,120 bytes |

The former uniform interpolation table and boundary table used 655,376 bytes.
The increase is 458,744 bytes, allocated once by the lazy table holder. There is
no new per-import or per-worker array. Seed construction scans the already-built
ordered boundaries; it does not evaluate a power function for each seed.

## Certified buckets

Let `B[q]` denote the stored first input representation assigned to output code
`q` by the original forward expression. Boundary construction is unchanged: it
adjusts an inverse estimate against the forward expression and uses a bounded
binary search if the short adjustment does not settle. `B[65536]` is the
positive-infinity sentinel.

The inherited 32-input-ULP guard accommodates the permitted variation between
the reference evaluations used at initialization and subsequent evaluations.
The existing forward-error bound covers `Math.pow`'s one-ULP contract and the
following rounded operations; it bounds the transition displacement below 27
input ULPs. The change preserves that guard and evaluates the original expression
within it. Java allows different conforming `Math` implementations, so an
inverse approximation alone is not an output-equality argument.
[Java Math specification](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Math.html#pow(double,double))

A bucket contains integer raw-bit values in the half-open interval `[start,end)`.
Its seed `q` is accepted only when:

```text
start > B[q] + 32
end <= B[min(q + 2, 65536)] - 32
```

Every input in that bucket is therefore more than 32 representable steps above
the transition to `q`. Because `end` is exclusive, the final input is at least
33 steps below the transition to `q + 2`. Only `q` and `q + 1` remain possible.
An uncertified bucket stores zero and evaluates the original expression instead.
The implementation does not depend on the observed absence of uncertified
buckets on the tested JVMs.

At runtime, the only unresolved distance is `B[q+1] - bits(input)`. An absolute
distance of at most 32 uses the reference. Otherwise, its sign bit selects zero
or one to add to `q`. This removes the data-dependent choice between two return
branches from the source; it is not a claim about every backend's machine code.

Both raw encodings in the subtraction are nonnegative signed longs. Their
difference cannot overflow or equal `Long.MIN_VALUE`, so `Math.abs` is safe.
Reading the seed with `& 0xffff` restores its unsigned value. For `q == 65535`,
the next boundary is positive infinity and the distance is positive; the result
cannot increment and wrap to zero.

## Memory lifetime

`BayerDemosaicer.estimateSrgb16TableBytes()` exposes the owning constant without
initializing the holder. `Cr3MemoryPolicy` adds shared storage once to both the
active development peak and the later selection/copy peak. It is independent of
the worker count. The optical estimate deducts only its own copy of these shared
bytes before comparing its workspace with selection and caller reservations.

The reserve applies to sRGB RGB16 and to RGB8 with enabled tone or eligible
active-area lens processing. This also covers lens-only RGB8 when a large later
copy dominates the temporary optical workspace. Profile-independent reservation
is conservative when metadata provides no matching profile. RAW, linear output
and RGB8 with both processing options disabled receive no shared encoder
addition. The optical workspace estimate itself still conservatively includes
transfer tables for linear optical processing.

The exported number is array payload. Headers, GC bookkeeping and heap-region
rounding are separate. For example, with one-MiB G1 regions, both new arrays
exceed half a region and each occupies a whole region. The general heap reserve
remains in place, and constrained-heap G1 imports are checked separately.
[G1 region and large-object rules](https://docs.oracle.com/en/java/javase/25/gctuning/garbage-first-g1-garbage-collector1.html)

## Verification boundaries

Production tests retain the original clipping, native-input, continuous-input,
output-boundary and guard-edge coverage. Additional checks exercise all 294,913
seed-grid edges and their adjacent doubles. A forced-zero seed exercises the
defensive fallback; its test is isolated and restores the entry in `finally`.

Memory tests independently sum the actual owning arrays, check one-byte AUTO
budget boundaries at both peaks, and exercise real decoder diagnostics for
RGB16, RGB8 tone/lens, subsampling, RAW and linear reads. The output comparison
runner checks complete-image fingerprints, dimensions, three channels and 16-bit
precision on every measured import. Tests cover finite selected inputs, while
the bucket certification supplies the argument for the remaining inputs.
