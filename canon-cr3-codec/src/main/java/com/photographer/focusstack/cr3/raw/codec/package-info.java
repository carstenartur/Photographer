/**
 * Internal CRX directory, entropy and Bayer reconstruction stages.
 *
 * <p>{@link com.photographer.focusstack.cr3.raw.codec.CrxSensorDecoder} validates a
 * bounded sample and provides row callbacks in sensor coordinates. The measured lossless
 * path decodes predicted samples. The measured C-RAW path synthesizes signed subbands and
 * converts the final lifted values to unsigned sensor codes. The caller handles raster
 * allocation and any later crop, scaling or color development.</p>
 */
package com.photographer.focusstack.cr3.raw.codec;
