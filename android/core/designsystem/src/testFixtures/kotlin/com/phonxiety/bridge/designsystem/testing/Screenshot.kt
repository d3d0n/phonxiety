package com.phonxiety.bridge.designsystem.testing

/**
 * The JUnit category of a test that renders the screen and records or compares a golden image.
 *
 * `-PskipScreenshots` excludes this category from every module's unit test run, so a feature
 * push still checks the logic next to a capture: the same class holds behaviour tests that only
 * compose the screen, and those are not in this category.
 */
interface Screenshot
