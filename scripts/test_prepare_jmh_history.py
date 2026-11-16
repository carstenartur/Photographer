#!/usr/bin/env python3

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import prepare_jmh_history as history  # noqa: E402


class PrepareJmhHistoryTest(unittest.TestCase):

    def test_known_benchmark_and_fixture_become_one_readable_series_name(self):
        result = history.transform_results([
            {
                "benchmark": (
                    "com.photographer.focusstack.cr3.benchmark."
                    "Cr3RawProcessingStagesBenchmark.demosaicBayer"
                ),
                "params": {"sample": "eosr-iso-low.cr3"},
                "primaryMetric": {"score": 12.5, "scoreUnit": "ms/op"},
            }
        ])

        self.assertEqual(
            "RAW processing · Demosaic Bayer data — EOS R · ISO 12800 · frame 1",
            result[0]["benchmark"],
        )
        self.assertNotIn("params", result[0])
        self.assertEqual(12.5, result[0]["primaryMetric"]["score"])

    def test_path_decode_keeps_the_same_series_across_the_method_rename(self):
        method = "com.photographer.focusstack.cr3.benchmark.Cr3RawProcessedBenchmark."
        params = {"sample": "sample.cr3"}
        current = history.friendly_series_name(method + "decodeRawProcessedFromPath", params)
        expected = "RAW pipeline · Decode display-ready sRGB (path input) — G5 X Mark II · ISO 125 · frame 1"
        self.assertEqual(expected, current)
        self.assertEqual(current, history.friendly_series_name(method + "decodeRawProcessedMmap", params))
        published = expected.replace("(path input)", "(memory-mapped input)")
        self.assertEqual(current, history.transform_published_series_name(published))
        self.assertEqual(current, history.transform_published_series_name(current))

    def test_fixture_labels_do_not_repeat_misleading_file_names(self):
        cases = {
            "sample-tele.cr3": "G5 X Mark II · ISO 125 · frame 2",
            "sample-wide.cr3": "G5 X Mark II · ISO 125 · frame 3",
            "eosr-iso-low.cr3": "EOS R · ISO 12800 · frame 1",
            "eosr-iso-mid.cr3": "EOS R · ISO 12800 · frame 2",
            "eosr-iso-high.cr3": "EOS R · ISO 12800 · frame 3",
        }

        for fixture, expected in cases.items():
            with self.subTest(fixture=fixture):
                self.assertEqual(expected, history.SAMPLE_LABELS[fixture])

    def test_non_sample_parameters_are_rendered_without_raw_json(self):
        result = history.transform_results([
            {
                "benchmark": (
                    "com.photographer.focusstack.cr3.raw.codec."
                    "CrxDecoderMicroStagesBenchmark.decodeRiceSymbols"
                ),
                "params": {
                    "variant": "ones-until-zero",
                    "strict": True,
                },
            }
        ])

        self.assertEqual(
            "CRX inner loop · Decode Rice symbol — Strict: yes · Variant: ones-until-zero",
            result[0]["benchmark"],
        )
        self.assertNotIn("params", result[0])

    def test_non_string_sample_parameter_is_not_dropped(self):
        result = history.transform_results([
            {
                "benchmark": (
                    "com.photographer.focusstack.cr3.benchmark."
                    "Cr3BoxParseBenchmark.parse"
                ),
                "params": {"sample": 42},
            }
        ])

        self.assertEqual(
            "Container · Parse ISO-BMFF boxes — Sample: 42",
            result[0]["benchmark"],
        )
        self.assertNotIn("params", result[0])

    def test_unknown_benchmark_has_a_readable_fallback(self):
        result = history.transform_results([
            {
                "benchmark": "example.NewCodecBenchmark.decodeTilePayload",
                "params": {"sample": "future-camera.cr3"},
            }
        ])

        self.assertEqual(
            "New Codec · Decode Tile Payload — future-camera.cr3",
            result[0]["benchmark"],
        )

    def test_input_is_not_mutated(self):
        source = [{
            "benchmark": (
                "com.photographer.focusstack.cr3.benchmark."
                "Cr3BoxParseBenchmark.parse"
            ),
            "params": {"sample": "sample.cr3"},
        }]

        history.transform_results(source)

        self.assertEqual("sample.cr3", source[0]["params"]["sample"])
        self.assertTrue(source[0]["benchmark"].endswith(".parse"))

    def test_existing_data_js_is_migrated_without_losing_history(self):
        readable_name = (
            "Container · Parse ISO-BMFF boxes — "
            "G5 X Mark II · ISO 125 · frame 1"
        )
        original = {
            "lastUpdate": 123,
            "repoUrl": "https://example.invalid/repo",
            "entries": {
                "Canon CR3 JMH Benchmarks": [
                    {
                        "commit": {"id": "old-commit"},
                        "date": 100,
                        "tool": "jmh",
                        "benches": [
                            {
                                "name": (
                                    "com.photographer.focusstack.cr3.benchmark."
                                    "Cr3BoxParseBenchmark.parse "
                                    "( {\"sample\":\"sample.cr3\"} )"
                                ),
                                "value": 2.5,
                                "unit": "ms/op",
                            }
                        ],
                    },
                    {
                        "commit": {"id": "newer-commit"},
                        "date": 200,
                        "tool": "jmh",
                        "benches": [
                            {
                                "name": readable_name,
                                "value": 2.1,
                                "unit": "ms/op",
                            }
                        ],
                    },
                ]
            },
        }
        document = "window.BENCHMARK_DATA = " + json.dumps(original) + ";\n"

        transformed = history.transform_data_js(document)
        transformed_twice = history.transform_data_js(transformed)
        # The publisher passes everything after the assignment prefix to JSON.parse.
        payload = json.loads(transformed.removeprefix("window.BENCHMARK_DATA = "))
        benches = payload["entries"]["Canon CR3 JMH Benchmarks"]

        self.assertEqual(transformed, transformed_twice)
        self.assertEqual("old-commit", benches[0]["commit"]["id"])
        self.assertEqual(2.5, benches[0]["benches"][0]["value"])
        self.assertEqual(readable_name, benches[0]["benches"][0]["name"])
        self.assertEqual(
            benches[0]["benches"][0]["name"],
            benches[1]["benches"][0]["name"],
        )

    def test_rejects_invalid_jmh_and_history_shapes(self):
        with self.assertRaisesRegex(ValueError, "JSON array"):
            history.transform_results({})
        with self.assertRaisesRegex(ValueError, "no benchmark name"):
            history.transform_results([{}])
        with self.assertRaisesRegex(ValueError, "params"):
            history.transform_results([
                {"benchmark": "example.SomeBenchmark.run", "params": []}
            ])
        with self.assertRaisesRegex(ValueError, "unsupported wrapper"):
            history.transform_data_js("const data = {}")
        with self.assertRaisesRegex(ValueError, "entries"):
            history.transform_history_data({})


if __name__ == "__main__":
    unittest.main()
