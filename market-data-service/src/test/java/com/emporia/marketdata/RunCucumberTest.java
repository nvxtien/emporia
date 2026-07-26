package com.emporia.marketdata;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features/alpaca_iex_market_data.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.emporia.marketdata")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty,summary")
class RunCucumberTest {
}
