#!/usr/bin/env python3

import unittest

import prepare_jmh_history as history


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
            "RAW processing · Demosaic Bayer data — EOS R · low ISO",
            result[0]["benchmark"],
        )
        self.assertNotIn("params", result[0])
        self.assertEqual(12.5, result[0]["primaryMetric"]["score"])

    def test_non_sample_parameters_remain_available_to_publisher(self):
        result = history.transform_results([
            {
                "benchmark": (
                    "com.photographer.focusstack.cr3.raw.codec."
                    "CrxDecoderMicroStagesBenchmark.decodeRiceSymbols"
                ),
                "params": {"variant": "ones-until-zero"},
            }
        ])

        self.assertEqual(
            "CRX inner loop · Decode Rice symbol",
            result[0]["benchmark"],
        )
        self.assertEqual(
            {"variant": "ones-until-zero"},
            result[0]["params"],
        )

    def test_unknown_benchmark_has_a_readable_fallback(self):
        result = history.transform_results([
            {
                "benchmark": "example.NewCodecBenchmark.decodeTilePayload",
                "params": {"sample": "future-camera.cr3"},
            }
        ])

        self.assertEqual(
            "New Codec · decode Tile Payload — future-camera.cr3",
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

    def test_rejects_invalid_jmh_shapes(self):
        with self.assertRaisesRegex(ValueError, "JSON array"):
            history.transform_results({})
        with self.assertRaisesRegex(ValueError, "no benchmark name"):
            history.transform_results([{}])
        with self.assertRaisesRegex(ValueError, "params"):
            history.transform_results([
                {"benchmark": "example.SomeBenchmark.run", "params": []}
            ])


if __name__ == "__main__":
    unittest.main()
