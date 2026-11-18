/**
 * High-level models and parser for the Canon CR3 container.
 *
 * <p>{@link com.photographer.focusstack.cr3.container.Cr3Parser} projects BMFF
 * boxes into track descriptors, sample byte ranges and optional Canon parameters.
 * The container layer describes the file structure. The codec interprets the
 * selected CRX sample using those ranges and parameters.</p>
 */
package com.photographer.focusstack.cr3.container;
