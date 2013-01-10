/*
 * Copyright (c) 2011 by the original author
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.powertac.server;

/**
 *  This is the Power TAC simulator weather service that queries an existing
 *  weather server for weather data and serves it to the brokers logged into 
 *  the game.
 *
 * @author Erik Onarheim, Govert Buijs
 */

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import org.apache.log4j.Logger;
import org.joda.time.DateTimeFieldType;
import org.joda.time.Instant;
import org.powertac.common.*;
import org.powertac.common.config.ConfigurableValue;
import org.powertac.common.exceptions.PowerTacException;
import org.powertac.common.interfaces.BrokerProxy;
import org.powertac.common.interfaces.InitializationService;
import org.powertac.common.interfaces.ServerConfiguration;
import org.powertac.common.interfaces.TimeslotPhaseProcessor;
import org.powertac.common.repo.TimeslotRepo;
import org.powertac.common.repo.WeatherForecastRepo;
import org.powertac.common.repo.WeatherReportRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;


@Service
public class WeatherService extends TimeslotPhaseProcessor implements
    InitializationService {

  static private Logger log = Logger.getLogger(WeatherService.class.getName());

  private Timeslot currentTime;
  private int lastStatefileTimestamp = 0;

  @ConfigurableValue(valueType = "String", description = "Location of weather data to be reported")
  private String weatherLocation = "rotterdam";

  @ConfigurableValue(valueType = "String", description = "Location of weather server rest url")
  private String serverUrl = "http://wolf-08.fbk.eur.nl:8080/WeatherServer/faces/index.xhtml";

  // If network requests should be made asynchronously or not.
  @ConfigurableValue(valueType = "Boolean", description = "If network calls to weather server should block until finished")
  private boolean blocking = true;

  @ConfigurableValue(valueType = "String", description = "Location of weather file (XML or state) or URL (state)")
  private String weatherData = "";

  // length of reports and forecasts. Can't really change this
  @ConfigurableValue(valueType = "Integer", description = "Timeslot interval to make requests")
  private int weatherReqInterval = 24;

  @ConfigurableValue(valueType = "Integer", description = "Length of forecasts (in hours)")
  private int forecastHorizon = 24; // 24 hours

  @Autowired
  private TimeslotRepo timeslotRepo;

  @Autowired
  private WeatherReportRepo weatherReportRepo;

  @Autowired
  private WeatherForecastRepo weatherForecastRepo;

  @Autowired
  private BrokerProxy brokerProxyService;

  @Autowired
  private ServerConfiguration serverProps;

  public int getWeatherReqInterval() {
    return weatherReqInterval;
  }

  public String getServerUrl() {
    return serverUrl;
  }

  public boolean isBlocking() {
    return blocking;
  }

  public int getForecastHorizon() {
    return forecastHorizon;
  }

  private String dateString(Timeslot time) {
    // Parse out year, month, day, and hour out of Timeslot
    int y = time.getStartInstant().get(DateTimeFieldType.year());
    int m = time.getStartInstant().get(DateTimeFieldType.monthOfYear());
    int d = time.getStartInstant().get(DateTimeFieldType.dayOfMonth());
    int h = time.getStartInstant().get(DateTimeFieldType.clockhourOfDay()) % 24;

    return String.format("%04d%02d%02d%02d", y, m, d, h);
  }

  private String dateStringLong(Timeslot time) {
    // Parse out year, month, day, and hour out of Timeslot
    int y = time.getStartInstant().get(DateTimeFieldType.year());
    int m = time.getStartInstant().get(DateTimeFieldType.monthOfYear());
    int d = time.getStartInstant().get(DateTimeFieldType.dayOfMonth());
    int h = time.getStartInstant().get(DateTimeFieldType.clockhourOfDay()) % 24;

    return String.format("%04d-%02d-%02d %02d:00", y, m, d, h);
  }

  private void resetCurrentTime() {
    currentTime = timeslotRepo.currentTimeslot();
  }

  // Make actual web request to the weather-server or get from file
  @Override
  public void activate(Instant time, int phaseNumber) {
    // Error check the request interval
    weatherReqInterval = Math.min(24, weatherReqInterval);

    long msec = time.getMillis();
    if (msec % (getWeatherReqInterval() * TimeService.HOUR) != 0) {
      log.info("WeatherService reports not time to grab weather data.");
    } else {
      log.info("Timeslot "
          + timeslotRepo.currentTimeslot().getId()
          + " WeatherService reports time to make request for weather data");
      requestData();
    }

    broadcastWeatherReports();
    broadcastWeatherForecasts();
  }

  private void requestData() {
    String currentMethod = "";
    try {
      Data data = null;

      if (weatherData != null && weatherData.endsWith(".xml")) {
        currentMethod = "xml file";
        WeatherXmlExtractor wxe = new WeatherXmlExtractor(weatherData);
        String weatherXml = wxe.extractPartialXml();
        data = parseXML(weatherXml);
      }

      if (weatherData != null && weatherData.endsWith(".state")) {
        currentMethod = "state file";
        StateFileExtractor sfe = new StateFileExtractor(weatherData);
        data = sfe.extractData();
      }

      if (data == null) {
        currentMethod = "web";
        data = webRequest();
      }

      log.debug("Got data via a " + currentMethod + " request");

      processData(data);
    }
    catch (Throwable e) {
      log.error("Unable to get weather from weather " + currentMethod);
      log.error(e.getMessage());
    }
  }

  private Data webRequest() {
    resetCurrentTime();
    String queryDate = dateString(currentTime);
    log.info("Query datetime value for REST call: " + queryDate);

    try {
      // Create a URLConnection object for a URL and send request
      URL url = new URL(getServerUrl() + "?weatherDate=" + queryDate
          + "&weatherLocation=" + weatherLocation);
      URLConnection conn = url.openConnection();

      // Get the response in xml
      BufferedReader input = new BufferedReader(new InputStreamReader(
          conn.getInputStream()));

      return parseXML(input);
    }
    catch (Exception e) {
      log.error("Exception Raised during newtork call: " + e.toString());
      System.out.println("Exception Raised: " + e.toString());
      e.printStackTrace();
      return null;
    }
  }

  private Data parseXML(Object input) {
    resetCurrentTime();

    if (input == null) {
      log.warn("Input to parseXML was null");
      return null;
    }

    try {
      // Set up stream and aliases
      XStream xstream = new XStream();
      xstream.alias("data", Data.class);
      xstream.alias("weatherReport", WeatherReport.class);
      xstream.alias("weatherForecast", WeatherForecastPrediction.class);

      // Xml uses attributes for more compact data
      xstream.useAttributeFor(WeatherReport.class);
      xstream.registerConverter(new WeatherReportConverter());

      // Xml uses attributes for more compact data
      xstream.useAttributeFor(WeatherForecastPrediction.class);
      xstream.registerConverter(new WeatherForecastConverter());

      // Unmarshall the xml input and place it into data container object
      Data data = null;
      if (input.getClass().equals(BufferedReader.class)) {
        data = (Data) xstream.fromXML((BufferedReader) input);
      } else if (input.getClass().equals(String.class)) {
        data = (Data) xstream.fromXML((String) input);
      }

      if (data == null) {
        return data;
      }
      if (data.weatherReports.size() != weatherReqInterval ||
          data.weatherForecasts.size() != weatherReqInterval * forecastHorizon){
        return null;
      }

      return data;
    }
    catch (Exception e) {
      log.error("Exception Raised parsing XML : " + e.toString());
      System.out.println("Exception Raised: " + e.toString());
      e.printStackTrace();
      return null;
    }
  }

  private void processData(Data data) {
    resetCurrentTime();

    processWeatherData(data);
    processForecastData(data);
  }

  private void processWeatherData(Data data) {
    for (WeatherReport report : data.getWeatherReports()) {
      weatherReportRepo.add(report);
    }

    log.info(data.getWeatherReports().size()
        + " WeatherReports fetched from xml response.");
    weatherReportRepo.runOnce();
  }

  private void processForecastData(Data data) {
    List<WeatherForecastPrediction> currentPredictions =
        new ArrayList<WeatherForecastPrediction>();
    int j = 0;
    for (WeatherForecastPrediction prediction: data.getWeatherForecasts()) {
      currentPredictions.add(prediction);

      if ((++j % forecastHorizon) == 0) {
        // Add a forecast to the repo, increment to the next timeslot
        WeatherForecast newForecast = new WeatherForecast(currentTime,
            currentPredictions);
        weatherForecastRepo.add(newForecast);
        currentPredictions = new ArrayList<WeatherForecastPrediction>();

        if (currentTime == null) {
          log.error("Null timeslot when adding forecasts to weatherForecastRepo");
        } else {
          currentTime = currentTime.getNext();
        }
      }
    }

    log.info(data.getWeatherForecasts().size()
        + " WeatherForecasts fetched from xml response.");
    weatherForecastRepo.runOnce();
  }

  private void broadcastWeatherReports() {
    WeatherReport report = null;
    try {
      report = weatherReportRepo.currentWeatherReport();
    }
    catch (PowerTacException e) {
      log.error("Weather Service reports Weather Report Repo empty");
    }
    if (report == null) {
      // In the event of an error return a default
      log.error("null weather-report!");
      brokerProxyService.broadcastMessage(new WeatherReport(timeslotRepo
          .currentTimeslot(), 0.0, 0.0, 0.0, 0.0));
    } else {
      brokerProxyService.broadcastMessage(report);
    }
  }

  private void broadcastWeatherForecasts() {
    WeatherForecast forecast = null;
    try {
      forecast = weatherForecastRepo.currentWeatherForecast();
    }
    catch (PowerTacException e) {
      log.error("Weather Service reports Weather Forecast Repo emtpy");
    }
    if (forecast == null) {
      log.error("null weather-forecast!");
      // In the event of an error return a default
      List<WeatherForecastPrediction> currentPredictions = new ArrayList<WeatherForecastPrediction>();
      for (int j = 1; j <= getForecastHorizon(); j++) {
        currentPredictions.add(new WeatherForecastPrediction(j, 0.0,
            0.0, 0.0, 0.0));
      }
      brokerProxyService.broadcastMessage(new WeatherForecast(
          timeslotRepo.currentTimeslot(), currentPredictions));
    } else {
      brokerProxyService.broadcastMessage(forecast);
    }
  }

  @Override
  public void setDefaults() {
  }

  @Override
  public String initialize(Competition competition, List<String> completedInits) {
    super.init();
    serverProps.configureMe(this);
    return "WeatherService";
  }

  // Helper classes
  private class WeatherReportConverter implements Converter {
    public WeatherReportConverter() {
      super();
    }

    @Override
    public boolean canConvert(Class clazz) {
      return clazz.equals(WeatherReport.class);
    }

    @Override
    public void marshal(Object source, HierarchicalStreamWriter writer,
                        MarshallingContext context) {
    }

    @SuppressWarnings("static-access")
    @Override
    public Object unmarshal(HierarchicalStreamReader reader,
                            UnmarshallingContext context) {
      String temp = reader.getAttribute("temp");
      String wind = reader.getAttribute("windspeed");
      String dir = reader.getAttribute("winddir");
      String cloudCvr = reader.getAttribute("cloudcover");
      //String location = reader.getAttribute("location");
      //String date = reader.getAttribute("date");

      WeatherReport wr = new WeatherReport(currentTime,
          Double.parseDouble(temp), Double.parseDouble(wind),
          Double.parseDouble(dir), Double.parseDouble(cloudCvr));

      try {
        currentTime = currentTime.getNext();
        return wr;
      }
      catch (Exception e) {
        return null;
      }
    }
  }

  private class WeatherForecastConverter implements Converter {
    public WeatherForecastConverter() {
      super();
    }

    @Override
    public boolean canConvert(Class clazz) {
      return clazz.equals(WeatherForecastPrediction.class);
    }

    @Override
    public void marshal(Object source, HierarchicalStreamWriter writer,
                        MarshallingContext context) {
    }

    @Override
    public Object unmarshal(HierarchicalStreamReader reader,
                            UnmarshallingContext context) {
      String id = reader.getAttribute("id");
      String temp = reader.getAttribute("temp");
      String wind = reader.getAttribute("windspeed");
      String dir = reader.getAttribute("winddir");
      String cloudCvr = reader.getAttribute("cloudcover");
      //String location = reader.getAttribute("location");
      //String origin = reader.getAttribute("origin");
      //String date = reader.getAttribute("date");

      return new WeatherForecastPrediction(Integer.parseInt(id),
          Double.parseDouble(temp), Double.parseDouble(wind),
          Double.parseDouble(dir), Double.parseDouble(cloudCvr));
    }
  }

  private class EnergyReport {

  }

  private class Data {
    private List<WeatherReport> weatherReports = new ArrayList<WeatherReport>();
    private List<WeatherForecastPrediction> weatherForecasts = new ArrayList<WeatherForecastPrediction>();
    private List<EnergyReport> energyReports = new ArrayList<EnergyReport>();

    public List<WeatherReport> getWeatherReports() {
      return weatherReports;
    }

    public void setWeatherReports(List<WeatherReport> weatherReports) {
      this.weatherReports = weatherReports;
    }

    public List<WeatherForecastPrediction> getWeatherForecasts() {
      return weatherForecasts;
    }

    public void setWeatherForecasts(
        List<WeatherForecastPrediction> weatherForecasts) {
      this.weatherForecasts = weatherForecasts;
    }

    public List<EnergyReport> getEnergyReports() {
      return energyReports;
    }

    public void setEnergyReports(List<EnergyReport> energyReports) {
      this.energyReports = energyReports;
    }
  }

  private class WeatherXmlExtractor {
    /**
     * This class extracts a part of a weather-xml, which contacins the weather
     * for the complete duration of the simulation.
     * It returns 24 reports and 24 * 24 forecasts
     */

    private NodeList nodeListRead = null;
    private Document documentWrite;
    private Element weatherReports;
    private Element weatherForecasts;

    public WeatherXmlExtractor(String fileName) {
      try {
        DocumentBuilderFactory docBuilderFactory = DocumentBuilderFactory
            .newInstance();
        DocumentBuilder docBuilderRead = docBuilderFactory.newDocumentBuilder();
        Document documentRead = docBuilderRead.parse(new File(fileName));
        Node rootNode = documentRead.getDocumentElement();
        nodeListRead = rootNode.getChildNodes();

        // Output document
        DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder docBuilderWrite = docFactory.newDocumentBuilder();
        documentWrite = docBuilderWrite.newDocument();
        documentWrite.setXmlStandalone(true);
        Element rootElement = documentWrite.createElement("data");
        documentWrite.appendChild(rootElement);

        // weatherReports and weatherForecasts elements
        weatherReports = documentWrite.createElement("weatherReports");
        rootElement.appendChild(weatherReports);
        weatherForecasts = documentWrite.createElement("weatherForecasts");
        rootElement.appendChild(weatherForecasts);
      }
      catch (Exception ignored) {}
    }

    private String extractPartialXml() {
      resetCurrentTime();
      String startDate = dateStringLong(currentTime);

      if (nodeListRead == null) {
        return null;
      }

      try {
        // Find 24 weatherReport starting at startDate
        for (int i = 0; i < nodeListRead.getLength(); i++) {
          Node currentNode = nodeListRead.item(i);

          if (!currentNode.getNodeName().equals("weatherReports")) {
            continue;
          }

          NodeList nodeListReports = currentNode.getChildNodes();
          findReports(nodeListReports, startDate);
        }

        // Find all weatherForecasts belonging to the 24 reports
        for (int i = 0; i < weatherReqInterval; i++) {
          for (int j = 0; j < nodeListRead.getLength(); j++) {
            Node currentNode = nodeListRead.item(j);

            if (!currentNode.getNodeName().equals("weatherForecasts")) {
              continue;
            }

            String origin = dateStringLong(currentTime);
            NodeList nodes = currentNode.getChildNodes();
            findForecasts(nodes, origin);
          }

          currentTime = currentTime.getNext();
        }

        if (weatherReports.getChildNodes().getLength() != weatherReqInterval ||
            weatherForecasts.getChildNodes().getLength() !=
                weatherReqInterval * forecastHorizon) {
          return null;
        }

        TransformerFactory transFactory = TransformerFactory.newInstance();
        Transformer transformer = transFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        StringWriter buffer = new StringWriter();
        transformer.transform(new DOMSource(documentWrite), new StreamResult(buffer));

        return buffer.toString();
      }
      catch (Exception e) {
        e.printStackTrace();
      }
      return null;
    }

    private void findReports(NodeList nodes, String startDate) {
      // Find reports starting at startDate, copy 24 reports to output document
      for (int j = 0; j < nodes.getLength(); j++) {
        Node report = nodes.item(j);
        if (!report.getNodeName().equals("weatherReport")) {
          continue;
        }

        String date = ((Element) report).getAttribute("date");
        if (date.compareTo(startDate) < 0) {
          continue;
        }

        Node temp = documentWrite.importNode(report, true);
        weatherReports.appendChild(temp);

        if (weatherReports.getChildNodes().getLength() == weatherReqInterval) {
          break;
        }
      }
    }

    private void findForecasts(NodeList nodes, String target) {
      // Find all forecasts belonging to a report-date, copy to output document
      for (int i = 0; i < nodes.getLength(); i++) {
        Node forecast = nodes.item(i);

        if (!forecast.getNodeName().equals("weatherForecast")) {
          continue;
        }

        String origin = ((Element) forecast).getAttribute("origin");
        if (!origin.equals(target)) {
          continue;
        }

        Node temp = documentWrite.importNode(forecast, true);
        weatherForecasts.appendChild(temp);
      }
    }
  }

  /**
   * This class extracts a part of a state file (or URL).
   * It returns $weatherReqInterval reports
   * and $weatherReqInterval * $forecastHorizon forecasts
   */
  private class StateFileExtractor {

    private URL weatherSource = null;
    private String report = "org.powertac.common.WeatherReport";
    private String forecast = "org.powertac.common.WeatherForecastPrediction";

    public StateFileExtractor (String weatherData) {
      resetCurrentTime();

      try {
        String urlName = weatherData;
        if (!urlName.contains(":")) {
          urlName = "file:" + urlName;
        }
        weatherSource = new URL(urlName);
      } catch (Exception ignored) {}
    }

    public Data extractData () {
      if (weatherSource == null) {
        return null;
      }

      BufferedReader br = null;
      try {
        Data data = new Data();
        br = new BufferedReader(
            new InputStreamReader(weatherSource.openStream()));

        String line;
        while ((line = br.readLine()) != null) {
          if (!line.contains(report) && !line.contains(forecast)){
            continue;
          }

          String[] temp = line.split("::");
          int stamp = Integer.parseInt(temp[1]);
          if (stamp <= lastStatefileTimestamp) {
            continue;
          }

          if (line.contains(report)) {
            data.getWeatherReports().add(
                new WeatherReport(
                    currentTime,
                    Double.parseDouble(temp[4]), Double.parseDouble(temp[5]),
                    Double.parseDouble(temp[6]), Double.parseDouble(temp[7])));

            currentTime = currentTime.getNext();
          }
          else if (line.contains(forecast)) {
            data.getWeatherForecasts().add(
                new WeatherForecastPrediction(
                    Integer.parseInt(temp[3]),
                    Double.parseDouble(temp[4]), Double.parseDouble(temp[5]),
                    Double.parseDouble(temp[6]), Double.parseDouble(temp[7])));
          }

          if (data.getWeatherReports().size() == forecastHorizon) {
            lastStatefileTimestamp = stamp;
          }
          if (data.getWeatherForecasts().size() ==
              weatherReqInterval * forecastHorizon) {
            break;
          }
        }

        return data;
      } catch (Exception e) {
        e.printStackTrace();
        return null;
      } finally {
        try {
          if (br != null) {
            br.close();
          }
        } catch (IOException ex) {
          ex.printStackTrace();
        }
      }
    }
  }
}