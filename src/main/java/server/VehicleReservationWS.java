package server;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebService;
import model.Reservation;
import model.Vehicle;

/**
 * JAX-WS web service implementation of DVRMS operations.
 *
 * <p>Business logic and concurrency behavior are preserved from Assignment 2.
 */
@WebService(serviceName = "VehicleReservationService")
public class VehicleReservationWS {
  // Shared across all server instances (static, same JVM)
  private static final ConcurrentHashMap<String, Double> customerBudget = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, Set<String>> crossOfficeCount =
      new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, Object> customerLocks = new ConcurrentHashMap<>();
  // In-process server registry for fast-path UDP (tests & same-JVM deployment)
  private static final ConcurrentHashMap<String, VehicleReservationWS> serverRegistry =
      new ConcurrentHashMap<>();
  // ==================== Constants ====================
  private static final double DEFAULT_BUDGET = 1000.0;
  private static final String DATE_FORMAT_HINT = "ddmmyyyy";
  private static final String MSG_INVALID_VEHICLE_TYPE = "FAIL: Invalid vehicle type. Allowed: Sedan, SUV, Truck";
  private static final String MSG_INVALID_DATE_FORMAT = "FAIL: Invalid date format. Use " + DATE_FORMAT_HINT;
  private static final String MSG_INVALID_DATE_RANGE = "FAIL: Invalid date range";
  private static final String MSG_INVALID_WAITLIST_DATE_RANGE = "FAIL: Invalid date range for waitlist";
  private static final String MSG_INVALID_VEHICLE_ID = "FAIL: Invalid vehicle ID format";
  private static final String MSG_UNKNOWN_OFFICE = "FAIL: Unknown office code: %s";
  private static final Map<String, String> VALID_VEHICLE_TYPES = buildVehicleTypeMap();
  // UDP Ports
  private static final Map<String, Integer> UDP_PORTS = buildUdpPorts();
  private static final String TOOLING_DEFAULT_SERVER_ID = "MTL";
  private static final int TOOLING_DEFAULT_UDP_PORT =
      PortConfig.officePort(1, TOOLING_DEFAULT_SERVER_ID);
  // ==================== Message Templates ====================
  private static final String MSG_UNAUTHORIZED_MANAGER =
      "FAIL: Manager %s not authorized for %s server";
  private static final String MSG_UNAUTHORIZED_CUSTOMER =
      "FAIL: Customer %s not authorized for %s server";
  private static final String MSG_VEHICLE_NOT_FOUND = "FAIL: Vehicle %s does not exist";
  private static final String MSG_VEHICLE_ADDED = "SUCCESS: Vehicle %s added.";
  private static final String MSG_VEHICLE_UPDATED = "SUCCESS: Vehicle %s updated.";
  private static final String MSG_INSUFFICIENT_BUDGET =
      "FAIL: Insufficient budget. Required: %.2f, Available: %.2f";
  private static final String MSG_CROSS_OFFICE_LIMIT =
      "FAIL: Already have 1 reservation at %s office (max allowed)";
  private static final String MSG_WAITLIST_OFFER =
      "WAITLIST: Vehicle %s not available. Add to waitlist? (yes/no)";
  private static final String MSG_NO_RESERVATION =
      "FAIL: No reservation found for customer %s on vehicle %s";
  private final String serverID; // MTL, WPG, or BNF
  private final String replicaIDForResult; // Numeric replica ID (1..4) for P2 RESULT messages
  // ==================== Data Structures ====================
  // Per-instance data (each server has its own)
  private final ConcurrentHashMap<String, Vehicle> vehicleDB;
  private final ConcurrentHashMap<String, List<Reservation>> reservations; // vehicleID -> reservations
  private final ConcurrentHashMap<String, List<WaitlistEntry>> waitList; // vehicleID -> waitlist
  private final ConcurrentHashMap<String, Object> vehicleLocks;
  private final BudgetManager budget = new BudgetManager();
  private final DateRules dateRules;
  private final ReliableUDPSender reliableSender = new ReliableUDPSender();

  // P2: Holdback queue for total ordering
  private int nextExpectedSeq = 0;
  private final PriorityQueue<PendingExecute> holdbackQueue =
      new PriorityQueue<>(Comparator.comparingInt(p -> p.seqNum));
  // P2: Byzantine simulation flag
  private volatile boolean byzantineMode = false;
  /** No-arg constructor required by JAX-WS tooling (e.g., wsgen). */
  public VehicleReservationWS() {
    this(TOOLING_DEFAULT_SERVER_ID, TOOLING_DEFAULT_UDP_PORT, false, null);
  }
  public VehicleReservationWS(String serverID, int udpPort) {
    this(serverID, udpPort, true, null);
  }
  public VehicleReservationWS(String serverID, int udpPort, String replicaIDForResult) {
    this(serverID, udpPort, true, replicaIDForResult);
  }
  private VehicleReservationWS(
      String serverID, int udpPort, boolean bootstrapRuntime, String replicaIDForResult) {
    this.serverID = serverID;
    this.replicaIDForResult = replicaIDForResult;
    this.vehicleDB = new ConcurrentHashMap<>();
    this.reservations = new ConcurrentHashMap<>();
    this.waitList = new ConcurrentHashMap<>();
    this.vehicleLocks = new ConcurrentHashMap<>();
    this.dateRules = new DateRules();

    if (bootstrapRuntime) {
      serverRegistry.put(serverID, this);
      // Initialize dummy data for demo
      initializeDummyData();
      // Start UDP listener unless explicitly disabled (e.g., unit tests)
      if (!Boolean.getBoolean("dvrms.disable.udp")) {
        new Thread(new UDPServer(this, udpPort)).start();
      }
      log("Server started. UDP Port: " + udpPort);
    }
  }
  // ==================== Constructor ====================

  private static Map<String, String> buildVehicleTypeMap() {
    Map<String, String> types = new HashMap<>();
    types.put("SEDAN", "Sedan");
    types.put("SUV", "SUV");
    types.put("TRUCK", "Truck");
    return Collections.unmodifiableMap(types);
  }

  private static Map<String, Integer> buildUdpPorts() {
    Map<String, Integer> ports = new LinkedHashMap<>();
    ports.put("MTL", PortConfig.officePort(1, "MTL"));
    ports.put("WPG", PortConfig.officePort(1, "WPG"));
    ports.put("BNF", PortConfig.officePort(1, "BNF"));
    return Collections.unmodifiableMap(ports);
  }

  // ==================== TEST SUPPORT ====================
  public static void resetGlobalStateForTests() {
    customerBudget.clear();
    crossOfficeCount.clear();
    customerLocks.clear();
  }
  // ==================== DUMMY DATA INITIALIZATION ====================

  /** Initializes pre-configured demo data. Each vehicle is UNIQUE (no quantity concept in Asg2). */
  private void initializeDummyData() {
    log("Initializing dummy database for " + serverID + " server...");
    switch (serverID) {
      case "MTL":
        initializeMTLData();
        break;
      case "WPG":
        initializeWPGData();
        break;
      case "BNF":
        initializeBNFData();
        break;
      default:
        log("Unknown server ID: " + serverID + ". No dummy data initialized.");
    }
    log("Dummy database initialization complete. Total vehicles: " + vehicleDB.size());
  }
  /** Montreal (MTL) - Urban rental office */
  private void initializeMTLData() {
    // Each vehicle is unique — one entry per vehicleID
    addDummyVehicle("MTL1001", "Sedan", "QC-ABC-101", 80.0);
    addDummyVehicle("MTL1002", "Sedan", "QC-ABC-102", 85.0);
    addDummyVehicle("MTL1003", "Sedan", "QC-ABC-103", 90.0);
    addDummyVehicle("MTL2001", "SUV", "QC-SUV-201", 120.0);
    addDummyVehicle("MTL2002", "SUV", "QC-SUV-202", 130.0);
    addDummyVehicle("MTL3001", "Truck", "QC-TRK-301", 150.0);
    log("MTL initialized with " + vehicleDB.size() + " unique vehicles");
  }
  /** Winnipeg (WPG) - Prairie rental office */
  private void initializeWPGData() {
    addDummyVehicle("WPG1001", "Sedan", "MB-SED-101", 75.0);
    addDummyVehicle("WPG1002", "Sedan", "MB-SED-102", 80.0);
    addDummyVehicle("WPG2001", "SUV", "MB-SUV-201", 110.0);
    addDummyVehicle("WPG2002", "SUV", "MB-SUV-202", 115.0);
    addDummyVehicle("WPG2003", "SUV", "MB-SUV-203", 125.0);
    addDummyVehicle("WPG3001", "Truck", "MB-TRK-301", 140.0);
    addDummyVehicle("WPG3002", "Truck", "MB-TRK-302", 145.0);
    log("WPG initialized with " + vehicleDB.size() + " unique vehicles");
  }
  /** Banff (BNF) - Mountain/Tourist rental office */
  private void initializeBNFData() {
    addDummyVehicle("BNF1001", "Sedan", "AB-SED-101", 85.0);
    addDummyVehicle("BNF2001", "SUV", "AB-SUV-201", 135.0);
    addDummyVehicle("BNF2002", "SUV", "AB-SUV-202", 145.0);
    addDummyVehicle("BNF2003", "SUV", "AB-SUV-203", 160.0);
    addDummyVehicle("BNF3001", "Truck", "AB-TRK-301", 155.0);
    addDummyVehicle("BNF3002", "Truck", "AB-TRK-302", 165.0);
    addDummyVehicle("BNF3003", "Truck", "AB-TRK-303", 180.0);
    log("BNF initialized with " + vehicleDB.size() + " unique vehicles");
  }
  /** Helper: add a single unique vehicle (no quantity parameter). */
  private void addDummyVehicle(
      String vehicleID, String vehicleType, String licensePlate, double price) {
    Vehicle vehicle = new Vehicle(vehicleID, vehicleType, licensePlate, price);
    vehicleDB.put(vehicleID, vehicle);
    log("Added dummy vehicle: " + vehicleID + " (" + vehicleType + ") @ $" + price);
  }
  private String rejectUnauthorizedManager(String operation, String managerID) {
    if (isAuthorizedManager(managerID)) {
      return null;
    }
    String msg = String.format(MSG_UNAUTHORIZED_MANAGER, managerID, serverID);
    log(operation + " - " + msg);
    return msg;
  }
  private String rejectUnauthorizedCustomer(String operation, String customerID) {
    if (isAuthorizedCustomer(customerID)) {
      return null;
    }
    String msg = String.format(MSG_UNAUTHORIZED_CUSTOMER, customerID, serverID);
    log(operation + " - " + msg);
    return msg;
  }
  private boolean isAuthorizedManager(String managerID) {
    return managerID != null && ServerIdRules.isValidManager(managerID, serverID);
  }
  private boolean isAuthorizedCustomer(String customerID) {
    return customerID != null && ServerIdRules.isValidCustomer(customerID, serverID);
  }
  private String rejectInvalidVehicleID(String operation, String vehicleID) {
    if (vehicleID == null || vehicleID.length() < 3) {
      log(operation + " - " + MSG_INVALID_VEHICLE_ID);
      return MSG_INVALID_VEHICLE_ID;
    }
    String office = ServerIdRules.extractOfficeID(vehicleID);
    if (!UDP_PORTS.containsKey(office)) {
      String msg = String.format(MSG_UNKNOWN_OFFICE, office);
      log(operation + " - " + msg);
      return msg;
    }
    return null;
  }
  private CustomerVehicleValidation validateCustomerAndVehicle(
      String operation, String customerID, String vehicleID) {
    String authError = rejectUnauthorizedCustomer(operation, customerID);
    if (authError != null) {
      return CustomerVehicleValidation.fail(authError);
    }
    String vehicleIdError = rejectInvalidVehicleID(operation, vehicleID);
    if (vehicleIdError != null) {
      return CustomerVehicleValidation.fail(vehicleIdError);
    }
    return CustomerVehicleValidation.ok(ServerIdRules.extractOfficeID(vehicleID));
  }
  private DateRange parseDateRange(
      String operation,
      String startDate,
      String endDate,
      String invalidFormatMessage,
      String invalidRangeMessage) {
    LocalDate start = dateRules.parseDate(startDate);
    LocalDate end = dateRules.parseDate(endDate);
    if (start == null || end == null) {
      log(operation + " - " + invalidFormatMessage);
      return DateRange.fail(invalidFormatMessage);
    }
    if (end.isBefore(start)) {
      log(operation + " - " + invalidRangeMessage);
      return DateRange.fail(invalidRangeMessage);
    }
    return new DateRange(start, end);
  }
  private DateRange parseDateRangeForReservation(
      String operation, String startDate, String endDate) {
    return parseDateRange(
        operation, startDate, endDate, MSG_INVALID_DATE_FORMAT, MSG_INVALID_DATE_RANGE);
  }
  private DateRange parseDateRangeForWaitlist(
      String operation, String startDate, String endDate) {
    return parseDateRange(
        operation, startDate, endDate, MSG_INVALID_WAITLIST_DATE_RANGE, MSG_INVALID_WAITLIST_DATE_RANGE);
  }
  // ==================== MANAGER OPERATIONS ====================
  @WebMethod
  public String addVehicle(
      @WebParam(name = "managerID") String managerID,
      @WebParam(name = "vehicleNumber") String vehicleNumber,
      @WebParam(name = "vehicleType") String vehicleType,
      @WebParam(name = "vehicleID") String vehicleID,
      @WebParam(name = "reservationPrice") double reservationPrice) {
    String authError = rejectUnauthorizedManager("addVehicle", managerID);
    if (authError != null) {
      return authError;
    }
    String vehicleIdError = rejectInvalidVehicleID("addVehicle", vehicleID);
    if (vehicleIdError != null) {
      return vehicleIdError;
    }
    if (!vehicleID.startsWith(serverID)) {
      String msg = "FAIL: Vehicle " + vehicleID + " does not belong to " + serverID + " server";
      log("addVehicle - " + msg);
      return msg;
    }
    String normalizedType = normalizeVehicleType(vehicleType);
    if (normalizedType == null) {
      String msg = MSG_INVALID_VEHICLE_TYPE;
      log("addVehicle - " + msg);
      return msg;
    }
    String result;
    synchronized (vehicleLock(vehicleID)) {
      Vehicle existing = vehicleDB.get(vehicleID);
      if (existing == null) {
        vehicleDB.put(
            vehicleID, new Vehicle(vehicleID, normalizedType, vehicleNumber, reservationPrice));
        result = String.format(MSG_VEHICLE_ADDED, vehicleID);
      } else {
        existing.setLicensePlate(vehicleNumber);
        existing.setVehicleType(normalizedType);
        existing.setReservationPrice(reservationPrice);
        result = String.format(MSG_VEHICLE_UPDATED, vehicleID);
      }
    }
    int assigned = processWaitList(vehicleID);
    if (assigned > 0) {
      result += " " + assigned + " customer(s) auto-assigned from waitlist.";
    }
    log("addVehicle - Manager: " + managerID + ", Vehicle: " + vehicleID + " - " + result);
    return result;
  }
  @WebMethod
  public String removeVehicle(
      @WebParam(name = "managerID") String managerID,
      @WebParam(name = "vehicleID") String vehicleID) {
    String authError = rejectUnauthorizedManager("removeVehicle", managerID);
    if (authError != null) {
      return authError;
    }
    String vehicleIdError = rejectInvalidVehicleID("removeVehicle", vehicleID);
    if (vehicleIdError != null) {
      return vehicleIdError;
    }
    double fallbackRefundAmount;
    String vehicleOffice = ServerIdRules.extractOfficeID(vehicleID);
    List<Reservation> removedReservations;
    int waitlistCleared;
    synchronized (vehicleLock(vehicleID)) {
      Vehicle vehicle = vehicleDB.get(vehicleID);
      if (vehicle == null) {
        String msg = String.format(MSG_VEHICLE_NOT_FOUND, vehicleID);
        log("removeVehicle - " + msg);
        return msg;
      }
      fallbackRefundAmount = vehicle.getReservationPrice();
      vehicleDB.remove(vehicleID);
      removedReservations = reservations.remove(vehicleID);
      List<WaitlistEntry> waitingCustomers = waitList.remove(vehicleID);
      waitlistCleared = waitingCustomers == null ? 0 : waitingCustomers.size();
    }
    int cancelledCount = 0;
    if (removedReservations != null) {
      for (Reservation reservation : removedReservations) {
        String customerID = reservation.getCustomerID();
        withCustomerLockResult(
            customerID,
            () -> {
              if (isCrossOffice(customerID, vehicleID)) {
                releaseRemoteOfficeSlot(customerID, vehicleOffice);
              }
              double refundAmount = resolveRefundAmount(reservation, fallbackRefundAmount, vehicleID);
              budget.refund(customerID, refundAmount);
              return null;
            });
        cancelledCount++;
      }
    }
    String result =
        "SUCCESS: Vehicle "
            + vehicleID
            + " removed. "
            + cancelledCount
            + " reservation(s) cancelled. "
            + waitlistCleared
            + " customer(s) removed from waitlist.";
    log("removeVehicle - Manager: " + managerID + ", Vehicle: " + vehicleID + " - " + result);
    return result;
  }
  @WebMethod
  public String listAvailableVehicle(@WebParam(name = "managerID") String managerID) {
    String authError = rejectUnauthorizedManager("listAvailableVehicle", managerID);
    if (authError != null) {
      return authError;
    }
    StringBuilder result = new StringBuilder();
    LocalDate today = LocalDate.now();
    for (Vehicle vehicle : vehicleDB.values()) {
      String status = resolveAvailabilityStatus(vehicle.getVehicleID(), today);
      appendLine(result, formatAvailableVehicleLine(vehicle, status));
    }
    if (result.length() == 0) {
      return "No vehicles available.";
    }
    log("listAvailableVehicle - Manager: " + managerID);
    return result.toString();
  }
  // ==================== CUSTOMER OPERATIONS ====================
  @WebMethod
  public String reserveVehicle(
      @WebParam(name = "customerID") String customerID,
      @WebParam(name = "vehicleID") String vehicleID,
      @WebParam(name = "startDate") String startDate,
      @WebParam(name = "endDate") String endDate) {
    CustomerVehicleValidation validation =
        validateCustomerAndVehicle("reserveVehicle", customerID, vehicleID);
    if (validation.failed()) {
      return validation.error;
    }
    String vehicleServer = validation.vehicleServer;
    if (!vehicleServer.equals(serverID)) {
      return reserveVehicleRemote(customerID, vehicleID, startDate, endDate, vehicleServer);
    }
    return reserveVehicleLocal(customerID, vehicleID, startDate, endDate, true);
  }
  /**
   * Local reservation logic (vehicle is on THIS server).
   *
   * @param applyBudget true when called from home server, false when called via UDP (budget is
   *     managed by the home server for remote reservations)
   */
  private String reserveVehicleLocal(
      String customerID, String vehicleID, String startDate, String endDate, boolean applyBudget) {
    if (applyBudget) {
      return withCustomerLockResult(
          customerID,
          () ->
              withVehicleLockResult(
                  vehicleID, () -> doReserveLocal(customerID, vehicleID, startDate, endDate, true)));
    }
    return withVehicleLockResult(
        vehicleID, () -> doReserveLocal(customerID, vehicleID, startDate, endDate, false));
  }
  /** Core reservation logic. Caller must hold the vehicle lock (and customer lock if applyBudget). */
  private String doReserveLocal(
      String customerID, String vehicleID, String startDate, String endDate, boolean applyBudget) {
    Vehicle vehicle = vehicleDB.get(vehicleID);
    if (vehicle == null) {
      String msg = String.format(MSG_VEHICLE_NOT_FOUND, vehicleID);
      log("reserveVehicle - " + msg);
      return msg;
    }
    DateRange range = parseDateRangeForReservation("reserveVehicle", startDate, endDate);
    if (range.failed()) {
      return range.error;
    }
    if (hasExistingReservation(customerID, vehicleID)) {
      String msg = "FAIL: Reservation already exists for " + vehicleID;
      log("reserveVehicle - " + msg);
      return msg;
    }
    String waitlistOffer = String.format(MSG_WAITLIST_OFFER, vehicleID);
    ReservationAssignment assignment =
        assignReservationIfEligible(
            customerID,
            vehicleID,
            startDate,
            endDate,
            range.start,
            range.end,
            vehicle,
            applyBudget,
            waitlistOffer);
    if (!assignment.success) {
      if (waitlistOffer.equals(assignment.failureMessage)) {
        log("reserveVehicle - Vehicle " + vehicleID + " not available for dates. Offering waitlist.");
      } else {
        log("reserveVehicle - " + assignment.failureMessage);
      }
      return assignment.failureMessage;
    }
    double price = assignment.price;
    String financialInfo =
        applyBudget
            ? "Cost: " + price + " Remaining budget: " + budget.get(customerID)
            : "Cost: " + price;
    String result = "SUCCESS: Reserved " + vehicleID + ". " + financialInfo;
    log("reserveVehicle - Customer: " + customerID + ", Vehicle: " + vehicleID + " - " + result);
    return result;
  }

  private ReservationAssignment assignReservationIfEligible(
      String customerID,
      String vehicleID,
      String startDate,
      String endDate,
      LocalDate start,
      LocalDate end,
      Vehicle vehicle,
      boolean applyBudget,
      String capacityFailureMessage) {
    if (!hasCapacity(vehicleID, start, end, null)) {
      return ReservationAssignment.fail(capacityFailureMessage);
    }
    double price = vehicle.getReservationPrice();
    if (applyBudget && !budget.canAfford(customerID, price)) {
      return ReservationAssignment.fail(
          String.format(MSG_INSUFFICIENT_BUDGET, price, budget.get(customerID)));
    }
    if (applyBudget) {
      budget.deduct(customerID, price);
    }
    reservationListForVehicle(vehicleID)
        .add(new Reservation(customerID, vehicleID, startDate, endDate, price));
    return ReservationAssignment.success(price);
  }
  /**
   * Remote reservation logic (vehicle is on ANOTHER server). Handles cross-office limit, UDP
   * communication, budget, and rollback.
   */
  private String reserveVehicleRemote(
      String customerID, String vehicleID, String startDate, String endDate, String targetServer) {
    double currentBudget = budget.get(customerID);
    String request = "RESERVE:" + customerID + ":" + vehicleID + ":" + startDate + ":" + endDate;
    return executeRemoteCustomerOperation(
        "reserveVehicle (remote)",
        customerID,
        vehicleID,
        targetServer,
        request,
        () ->
            acquireRemoteOfficeSlot(customerID, targetServer)
                ? null
                : String.format(MSG_CROSS_OFFICE_LIMIT, targetServer),
        (response, responseType) -> {
          if (responseType == RemoteResponseType.SUCCESS) {
            String processed =
                handleRemoteReserveSuccess(customerID, vehicleID, targetServer, response, currentBudget);
            if (classifyResponse(processed) == RemoteResponseType.FAIL) {
              return processed;
            }
            return processed;
          }
          if (responseType == RemoteResponseType.WAITLIST) {
            return releaseRemoteSlotAndReturn(customerID, targetServer, response);
          }
          releaseRemoteOfficeSlot(customerID, targetServer);
          return response;
        });
  }
  private String handleRemoteReserveSuccess(
      String customerID,
      String vehicleID,
      String targetServer,
      String successResponse,
      double currentBudget) {
    double price = extractPrice(successResponse);
    if (currentBudget < price) {
      String msg = String.format(MSG_INSUFFICIENT_BUDGET, price, currentBudget);
      if (rollbackRemoteReservationAndReleaseSlot(customerID, vehicleID, targetServer)) {
        log("reserveVehicle (remote) - " + msg);
        return msg;
      }
      // Conservative hold: keep cross-office quota unchanged when rollback is uncertain.
      String rollbackMsg =
          msg
              + " Rollback to "
              + targetServer
              + " failed or uncertain; reservation state may still exist.";
      log("reserveVehicle (remote) - " + rollbackMsg);
      return rollbackMsg;
    }
    budget.deduct(customerID, price);
    return successResponse + " Remaining budget: " + budget.get(customerID);
  }
  private boolean rollbackRemoteReservation(
      String customerID, String vehicleID, String targetServer) {
    String cancelResponse = sendUDPRequest(targetServer, "CANCEL:" + customerID + ":" + vehicleID);
    if (classifyResponse(cancelResponse) == RemoteResponseType.SUCCESS) {
      return true;
    }
    log(
        "reserveVehicle (remote) - Rollback cancel response from "
            + targetServer
            + ": "
            + cancelResponse);
    return false;
  }
  @WebMethod
  public String updateReservation(
      @WebParam(name = "customerID") String customerID,
      @WebParam(name = "vehicleID") String vehicleID,
      @WebParam(name = "newStartDate") String newStartDate,
      @WebParam(name = "newEndDate") String newEndDate) {
    CustomerVehicleValidation validation =
        validateCustomerAndVehicle("updateReservation", customerID, vehicleID);
    if (validation.failed()) {
      return validation.error;
    }
    String vehicleServer = validation.vehicleServer;
    if (!vehicleServer.equals(serverID)) {
      return updateReservationRemote(customerID, vehicleID, newStartDate, newEndDate, vehicleServer);
    }
    return atomicUpdateLocal(customerID, vehicleID, newStartDate, newEndDate, true);
  }
  /**
   * ATOMIC update — the critical Assignment 2 requirement.
   *
   * <p>All three steps must succeed or none:
   *
   * <ol>
   *   <li>(i) Check reservation or waitlist entry exists for this customer + vehicle
   *   <li>(ii) Check vehicle is available for the new date range
   *   <li>(iii) Update the reservation dates, or promote waitlist entry to reservation
   * </ol>
   *
   * <p>This method is called both directly (local path) and from handleUDPRequest() (remote path).
   * Atomicity is enforced by taking the per-vehicle lock on the data-owning server.
   *
   * @param applyBudget true when called from home server (budget managed locally),
   *                    false when called via UDP (budget managed by the home server)
   */
  private String atomicUpdateLocal(
      String customerID, String vehicleID, String newStartDate, String newEndDate,
      boolean applyBudget) {
    String activeUpdateResult;
    boolean shouldRunCleanup;
    synchronized (vehicleLock(vehicleID)) {
      DateRange range = parseDateRangeForReservation("updateReservation", newStartDate, newEndDate);
      if (range.failed()) {
        return range.error;
      }
      LocalDate newStart = range.start;
      LocalDate newEnd = range.end;
      ActiveUpdateResult activeResult =
          updateActiveReservationDatesIfPresent(
              customerID, vehicleID, newStartDate, newEndDate, newStart, newEnd, applyBudget);
      if (activeResult.failed()) {
        return activeResult.failureMessage;
      }
      if (!activeResult.updated) {
        return updateWaitlistEntryOrPromote(
            customerID, vehicleID, newStartDate, newEndDate, newStart, newEnd, applyBudget);
      }
      activeUpdateResult = activeResult.successMessage;
      shouldRunCleanup = activeResult.shouldRunCleanup;
    }
    activeUpdateResult =
        appendAtomicUpdateWaitlistCleanup(activeUpdateResult, vehicleID, shouldRunCleanup);
    log(
        "updateReservation - Customer: "
            + customerID
            + ", Vehicle: "
            + vehicleID
            + " - "
            + activeUpdateResult);
    return activeUpdateResult;
  }

  private ActiveUpdateResult updateActiveReservationDatesIfPresent(
      String customerID,
      String vehicleID,
      String newStartDate,
      String newEndDate,
      LocalDate newStart,
      LocalDate newEnd,
      boolean applyBudget) {
    List<Reservation> vehicleReservations = reservations.get(vehicleID);
    Optional<Reservation> reservation =
        vehicleReservations != null
            ? ReservationQueries.findReservation(vehicleReservations, customerID)
            : Optional.empty();
    if (!reservation.isPresent()) {
      return ActiveUpdateResult.notFound();
    }
    Reservation current = reservation.get();
    if (!hasCapacity(vehicleID, newStart, newEnd, current)) {
      String msg = "FAIL: Dates conflict with another reservation";
      log("updateReservation - " + msg);
      return ActiveUpdateResult.fail(msg);
    }
    boolean datesChanged =
        !newStartDate.equals(current.getStartDate()) || !newEndDate.equals(current.getEndDate());
    current.setStartDate(newStartDate);
    current.setEndDate(newEndDate);
    double price = resolveRefundAmount(current, 0, vehicleID);
    String budgetInfo = applyBudget ? " Remaining budget: " + budget.get(customerID) : "";
    String result =
        "SUCCESS: Reservation updated. New dates: "
            + newStartDate
            + " to "
            + newEndDate
            + " Cost: "
            + price
            + budgetInfo;
    return ActiveUpdateResult.updated(result, datesChanged);
  }

  private String updateWaitlistEntryOrPromote(
      String customerID,
      String vehicleID,
      String newStartDate,
      String newEndDate,
      LocalDate newStart,
      LocalDate newEnd,
      boolean applyBudget) {
    List<WaitlistEntry> waiting = waitList.get(vehicleID);
    if (waiting != null) {
      int waitlistIndex = WaitlistQueries.findFirstByCustomer(waiting, customerID);
      if (waitlistIndex >= 0) {
        Vehicle vehicle = vehicleDB.get(vehicleID);
        if (vehicle == null) {
          waiting.remove(waitlistIndex);
          String msg = String.format(MSG_VEHICLE_NOT_FOUND, vehicleID);
          log("updateReservation - " + msg);
          return msg;
        }
        ReservationAssignment assignment =
            assignReservationIfEligible(
                customerID,
                vehicleID,
                newStartDate,
                newEndDate,
                newStart,
                newEnd,
                vehicle,
                applyBudget,
                null);
        if (assignment.success) {
          // Vehicle available for new dates — promote waitlist to reservation
          waiting.remove(waitlistIndex);
          String budgetInfo =
              applyBudget ? "Remaining budget: " + budget.get(customerID) : "Cost: " + assignment.price;
          String result = "PROMOTED: Waitlist to reservation. " + budgetInfo;
          log("updateReservation - Customer: " + customerID + ", Vehicle: " + vehicleID + " - " + result);
          return result;
        }
        if (assignment.failureMessage != null) {
          log("updateReservation - " + assignment.failureMessage);
          return assignment.failureMessage;
        }
        // Vehicle not available — update waitlist entry dates
        waiting.remove(waitlistIndex);
        waiting.add(new WaitlistEntry(customerID, newStartDate, newEndDate));
        String result = "SUCCESS: Waitlist entry updated. New dates: " + newStartDate + " to " + newEndDate;
        log("updateReservation - Customer: " + customerID + ", Vehicle: " + vehicleID + " - " + result);
        return result;
      }
    }
    String msg = String.format(MSG_NO_RESERVATION, customerID, vehicleID);
    log("updateReservation - " + msg);
    return msg;
  }

  private String appendAtomicUpdateWaitlistCleanup(
      String activeUpdateResult, String vehicleID, boolean shouldRunCleanup) {
    if (!shouldRunCleanup) {
      return activeUpdateResult;
    }
    int assigned = processWaitList(vehicleID);
    if (assigned > 0) {
      return activeUpdateResult + ". " + assigned + " customer(s) auto-assigned from waitlist.";
    }
    return activeUpdateResult;
  }
  /**
   * Remote update logic (vehicle is on ANOTHER server). Handles cross-office quota and budget
   * when a waitlist entry is promoted to a reservation on the remote server.
   */
  private String updateReservationRemote(
      String customerID, String vehicleID, String newStartDate, String newEndDate,
      String targetServer) {
    String request =
        "ATOMIC_UPDATE:" + customerID + ":" + vehicleID + ":" + newStartDate + ":" + newEndDate;
    return executeRemoteCustomerOperation(
        "updateReservation (remote)",
        customerID,
        vehicleID,
        targetServer,
        request,
        null,
        (response, responseType) -> {
          String finalResponse = response;
          if (responseType == RemoteResponseType.PROMOTED) {
            // Waitlist was promoted to reservation on the remote server.
            // Need to handle cross-office quota and budget on the home server.
            if (!acquireRemoteOfficeSlot(customerID, targetServer)) {
              // Can't take another slot at this office — rollback the remote reservation
              rollbackRemoteReservation(customerID, vehicleID, targetServer);
              return String.format(MSG_CROSS_OFFICE_LIMIT, targetServer);
            }
            double price = extractPrice(finalResponse);
            double currentBudget = budget.get(customerID);
            if (currentBudget < price) {
              // Insufficient budget — rollback
              String msg = String.format(MSG_INSUFFICIENT_BUDGET, price, currentBudget);
              rollbackRemoteReservationAndReleaseSlot(customerID, vehicleID, targetServer);
              return msg;
            }
            budget.deduct(customerID, price);
            finalResponse = "PROMOTED: Waitlist to reservation. Remaining budget: " + budget.get(customerID);
          }
          if (RemoteResponseRules.isReservationUpdatedSuccess(finalResponse)) {
            finalResponse = finalResponse + " Remaining budget: " + budget.get(customerID);
          }
          return finalResponse;
        });
  }
  @WebMethod
  public String cancelReservation(
      @WebParam(name = "customerID") String customerID,
      @WebParam(name = "vehicleID") String vehicleID) {
    CustomerVehicleValidation validation =
        validateCustomerAndVehicle("cancelReservation", customerID, vehicleID);
    if (validation.failed()) {
      return validation.error;
    }
    String vehicleServer = validation.vehicleServer;
    if (!vehicleServer.equals(serverID)) {
      return executeRemoteCustomerOperation(
          "cancelReservation (remote)",
          customerID,
          vehicleID,
          vehicleServer,
          "CANCEL:" + customerID + ":" + vehicleID,
          null,
          (response, responseType) -> {
            String finalResponse = response;
            if (responseType == RemoteResponseType.SUCCESS) {
              releaseRemoteOfficeSlot(customerID, vehicleServer);
              double refund = extractPrice(finalResponse);
              budget.refund(customerID, refund);
              finalResponse = appendNewBudgetInfo(finalResponse, budget.get(customerID));
            }
            return finalResponse;
          });
    }
    return cancelReservationLocal(customerID, vehicleID, true);
  }
  /**
   * Local cancellation logic.
   *
   * @param applyBudget true when called from home server (budget managed locally),
   *                    false when called via UDP (budget managed by the home server)
   */
  private String cancelReservationLocal(String customerID, String vehicleID, boolean applyBudget) {
    final double[] refundHolder = new double[1];
    final double[] newBudgetHolder = new double[1];
    String immediateResult =
        withCustomerThenVehicleLockResult(
            customerID,
            vehicleID,
            () -> {
              List<Reservation> vehicleReservations = reservations.get(vehicleID);
              Optional<Reservation> reservation = (vehicleReservations != null)
                  ? ReservationQueries.findReservation(vehicleReservations, customerID)
                  : Optional.empty();
              if (reservation.isPresent()) {
                // Cancel active reservation
                Reservation reservationRecord = reservation.get();
                vehicleReservations.remove(reservationRecord);
                refundHolder[0] = resolveRefundAmount(reservationRecord, 0, vehicleID);
                if (applyBudget) {
                  budget.refund(customerID, refundHolder[0]);
                }
                newBudgetHolder[0] = applyBudget ? budget.get(customerID) : -1;
                return null;
              }
              // Fall back to waitlist removal
              List<WaitlistEntry> waiting = waitList.get(vehicleID);
              int waitlistIndex = WaitlistQueries.removeFirstByCustomer(waiting, customerID);
              if (waitlistIndex >= 0) {
                String result = "SUCCESS: Removed from waitlist for " + vehicleID;
                log("cancelReservation - Customer: " + customerID + ", Vehicle: " + vehicleID + " - " + result);
                return result;
              }
              String failMessage = String.format(MSG_NO_RESERVATION, customerID, vehicleID);
              log("cancelReservation - " + failMessage);
              return failMessage;
            });
    if (immediateResult != null) {
      return immediateResult;
    }
    int assigned = processWaitList(vehicleID);
    String result;
    if (applyBudget) {
      result =
          "SUCCESS: Reservation cancelled. Refund: "
              + refundHolder[0]
              + ". New budget: "
              + newBudgetHolder[0];
    } else {
      result = "SUCCESS: Reservation cancelled. Refund: " + refundHolder[0];
    }
    if (assigned > 0) {
      result += ". " + assigned + " customer(s) auto-assigned from waitlist.";
    }
    log("cancelReservation - Customer: " + customerID + ", Vehicle: " + vehicleID + " - " + result);
    return result;
  }
  private double resolveRefundAmount(
      Reservation reservation, double fallbackVehiclePrice, String vehicleID) {
    // Prefer booked price when present; fall back for legacy/abnormal records.
    double pricePaid = reservation.getPricePaid();
    if (pricePaid > 0) {
      return pricePaid;
    }
    if (fallbackVehiclePrice > 0) {
      return fallbackVehiclePrice;
    }
    Vehicle vehicle = vehicleDB.get(vehicleID);
    return vehicle != null ? vehicle.getReservationPrice() : 0;
  }
  @WebMethod
  public String findVehicle(
      @WebParam(name = "customerID") String customerID,
      @WebParam(name = "vehicleType") String vehicleType) {
    String authError = rejectUnauthorizedCustomer("findVehicle", customerID);
    if (authError != null) {
      return authError;
    }
    String normalizedType = normalizeVehicleType(vehicleType);
    if (normalizedType == null) {
      String msg = MSG_INVALID_VEHICLE_TYPE;
      log("findVehicle - " + msg);
      return msg;
    }
    String finalResult =
        aggregateLocalAndRemoteResponses(findVehicleLocal(normalizedType), "FIND:SYSTEM:" + normalizedType);
    if (finalResult.isEmpty()) {
      finalResult = "No vehicles found of type: " + normalizedType;
    }
    log("findVehicle - Customer: " + customerID + ", Type: " + normalizedType);
    return finalResult;
  }
  @WebMethod
  public String listCustomerReservations(@WebParam(name = "customerID") String customerID) {
    String authError = rejectUnauthorizedCustomer("listCustomerReservations", customerID);
    if (authError != null) {
      return authError;
    }
    String finalResult =
        aggregateLocalAndRemoteResponses(
            listCustomerReservationsLocal(customerID), "LISTRES:SYSTEM:" + customerID);
    if (finalResult.isEmpty()) {
      finalResult = "No reservations found for " + customerID;
    }
    log("listCustomerReservations - Customer: " + customerID);
    return finalResult;
  }
  // ==================== WAITLIST SUPPORT ====================
  @WebMethod
  public String addToWaitList(
      @WebParam(name = "customerID") String customerID,
      @WebParam(name = "vehicleID") String vehicleID,
      @WebParam(name = "startDate") String startDate,
      @WebParam(name = "endDate") String endDate) {
    CustomerVehicleValidation validation =
        validateCustomerAndVehicle("addToWaitList", customerID, vehicleID);
    if (validation.failed()) {
      return validation.error;
    }
    String vehicleServer = validation.vehicleServer;
    if (!vehicleServer.equals(serverID)) {
      String request = "WAITLIST:" + customerID + ":" + vehicleID + ":" + startDate + ":" + endDate;
      String response = sendUDPRequest(vehicleServer, request);
      log(
          "addToWaitList (remote) - Customer: "
              + customerID
              + ", Vehicle: "
              + vehicleID
              + " - "
              + response);
      return response;
    }
    String result = addToWaitListLocal(customerID, vehicleID, startDate, endDate);
    log("addToWaitList - Customer: " + customerID + ", Vehicle: " + vehicleID + " - " + result);
    return result;
  }
  /** Add customer to local waitlist for a vehicle. */
  String addToWaitListLocal(
      String customerID, String vehicleID, String startDate, String endDate) {
    synchronized (vehicleLock(vehicleID)) {
      if (parseDateRangeForWaitlist("addToWaitList", startDate, endDate).failed()) {
        return MSG_INVALID_WAITLIST_DATE_RANGE;
      }
      if (hasExistingReservation(customerID, vehicleID)) {
        return "FAIL: Reservation already exists for " + vehicleID;
      }
      List<WaitlistEntry> entries = waitlistForVehicle(vehicleID);
      boolean duplicate = WaitlistQueries.containsExact(entries, customerID, startDate, endDate);
      if (duplicate) {
        return "FAIL: Already on waitlist for " + vehicleID + " for the provided dates";
      }
      entries.add(new WaitlistEntry(customerID, startDate, endDate));
      return "SUCCESS: Added to waitlist for " + vehicleID;
    }
  }
  // ==================== UDP HANDLING ====================
  /**
   * Dispatches incoming UDP requests to the appropriate handler. Called by UDPServer when a message
   * arrives.
   *
   * <p>Supported operations: RESERVE, CANCEL, FIND, LISTRES, WAITLIST, ATOMIC_UPDATE (new in Asg2)
   */
  String handleUDPRequest(String request) {
    if (request == null || request.trim().isEmpty()) {
      return "FAIL: Empty request";
    }
    String[] parts = request.split(":", -1);
    if (parts.length == 0) {
      return "FAIL: Invalid request";
    }
    String operation = parts[0];
    switch (operation) {
      case "ADDVEHICLE":
        if (parts.length < 6) {
          return "FAIL: Invalid add vehicle request";
        }
        try {
          return addVehicle(parts[1], parts[2], parts[3], parts[4], Double.parseDouble(parts[5]));
        } catch (NumberFormatException e) {
          return "FAIL: Invalid vehicle price";
        }
      case "REMOVEVEHICLE":
        return (parts.length >= 3)
            ? removeVehicle(parts[1], parts[2])
            : "FAIL: Invalid remove vehicle request";
      case "LISTAVAILABLE":
        return (parts.length >= 2)
            ? listAvailableVehicle(parts[1])
            : "FAIL: Invalid list available request";
      case "RESERVE":
        return (parts.length >= 5)
            ? reserveVehicleLocal(parts[1], parts[2], parts[3], parts[4], false)
            : "FAIL: Invalid reserve request";
      case "RESERVE_EXECUTE":
        return (parts.length >= 5)
            ? reserveVehicle(parts[1], parts[2], parts[3], parts[4])
            : "FAIL: Invalid reserve execute request";
      case "CANCEL":
        return (parts.length >= 3)
            ? cancelReservationLocal(parts[1], parts[2], false)
            : "FAIL: Invalid cancel request";
      case "CANCEL_EXECUTE":
        return (parts.length >= 3)
            ? cancelReservation(parts[1], parts[2])
            : "FAIL: Invalid cancel execute request";
      case "FIND":
        if (parts.length >= 3) {
          if ("SYSTEM".equals(parts[1])) {
            // Inter-office request — return local vehicles only (avoid recursion)
            String normalized = normalizeVehicleType(parts[2]);
            return normalized != null ? findVehicleLocal(normalized) : "FAIL: Invalid vehicle type";
          } else {
            // Request from Sequencer — aggregate across all offices
            return findVehicle(parts[1], parts[2]);
          }
        }
        return "FAIL: Invalid find request";
      case "LISTRES":
        if (parts.length >= 2) {
          if ("SYSTEM".equals(parts[1]) && parts.length >= 3) {
            // Inter-office request — return local reservations only (avoid recursion)
            return listCustomerReservationsLocal(parts[2]);
          } else {
            // Request from Sequencer — aggregate across all offices
            return listCustomerReservations(parts[1]);
          }
        }
        return "FAIL: Invalid list request";
      case "WAITLIST":
        return (parts.length >= 5)
            ? addToWaitListLocal(parts[1], parts[2], parts[3], parts[4])
            : "FAIL: Invalid waitlist request";
      case "ATOMIC_UPDATE":
        return (parts.length >= 5)
            ? atomicUpdateLocal(parts[1], parts[2], parts[3], parts[4], false)
            : "FAIL: Invalid atomic update request";
      case "ATOMIC_UPDATE_EXECUTE":
        return (parts.length >= 5)
            ? updateReservation(parts[1], parts[2], parts[3], parts[4])
            : "FAIL: Invalid atomic update execute request";
      case "SET_BYZANTINE":
        byzantineMode = parts.length >= 2 && "true".equalsIgnoreCase(parts[1]);
        return "OK:BYZANTINE=" + byzantineMode;
      case "HEARTBEAT_CHECK":
        return "HEARTBEAT_ACK:" + serverID + ":" + nextExpectedSeq;
      case "STATE_REQUEST":
        return "STATE_TRANSFER:" + getStateSnapshot();
      default:
        return "FAIL: Unknown operation";
    }
  }
  // ==================== HELPER: Cross-Office Count ====================
  private boolean acquireRemoteOfficeSlot(String customerID, String officeID) {
    Set<String> remoteOffices =
        crossOfficeCount.computeIfAbsent(customerID, k -> ConcurrentHashMap.newKeySet());
    return remoteOffices.add(officeID);
  }
  private void releaseRemoteOfficeSlot(String customerID, String officeID) {
    Set<String> remoteOffices = crossOfficeCount.get(customerID);
    if (remoteOffices == null) {
      return;
    }
    remoteOffices.remove(officeID);
    if (remoteOffices.isEmpty()) {
      crossOfficeCount.remove(customerID, remoteOffices);
    }
  }
  private boolean isCrossOffice(String customerID, String vehicleID) {
    if (customerID == null || vehicleID == null || customerID.length() < 3 || vehicleID.length() < 3) {
      return false;
    }
    return !ServerIdRules.extractOfficeID(customerID).equals(ServerIdRules.extractOfficeID(vehicleID));
  }
  // ==================== HELPER: Budget Manager ====================

  /** Check if a customer already has a reservation on a vehicle (any dates). */
  private boolean hasExistingReservation(String customerID, String vehicleID) {
    return ReservationQueries.hasExistingReservation(reservations, customerID, vehicleID);
  }
  // ==================== HELPER: Reservation Queries ====================

  /**
   * Check if vehicle has capacity for the given date range. Since each vehicle is UNIQUE (Asg2),
   * capacity = 1. Callers must ensure the vehicle exists before calling this method.
   *
   * @param exclude Reservation to exclude from overlap check (for update operations)
   * @return true if no other reservation overlaps the given dates
   */
  private boolean hasCapacity(
      String vehicleID, LocalDate start, LocalDate end, Reservation exclude) {
    return !ReservationQueries.hasAnyOverlap(reservations, dateRules, vehicleID, start, end, exclude);
  }

  /**
   * Process the waitlist for a vehicle after availability changes. Auto-assigns the first eligible
   * waiting customer.
   *
   * @return number of customers auto-assigned
   */
  private int processWaitList(String vehicleID) {
    int assigned = 0;
    int index = 0;
    while (true) {
      WaitlistEntry candidate = fetchWaitlistCandidate(vehicleID, index);
      if (candidate == null) {
        return assigned;
      }
      int currentIndex = index;
      WaitlistDecision decision =
          withCustomerThenVehicleLockResult(
              candidate.customerID,
              vehicleID,
              () -> {
                List<WaitlistEntry> waiting = waitList.get(vehicleID);
                if (waiting == null) {
                  return WaitlistDecision.stopNoAssignment();
                }
                int idx = revalidateWaitlistCandidateIndex(waiting, candidate);
                if (idx < 0) {
                  return WaitlistDecision.continueFrom(currentIndex);
                }
                return processWaitlistCandidate(vehicleID, waiting, idx, candidate);
              });
      assigned += decision.assignedDelta;
      if (decision.stopProcessing) {
        return assigned;
      }
      index = decision.nextIndex;
    }
  }

  private WaitlistEntry fetchWaitlistCandidate(String vehicleID, int index) {
    synchronized (vehicleLock(vehicleID)) {
      List<WaitlistEntry> waiting = waitList.get(vehicleID);
      if (waiting == null || index >= waiting.size()) {
        return null;
      }
      return waiting.get(index);
    }
  }

  private int revalidateWaitlistCandidateIndex(List<WaitlistEntry> waiting, WaitlistEntry candidate) {
    return WaitlistQueries.findExact(
        waiting, candidate.customerID, candidate.startDate, candidate.endDate);
  }

  private WaitlistDecision processWaitlistCandidate(
      String vehicleID, List<WaitlistEntry> waiting, int index, WaitlistEntry candidate) {
    Vehicle vehicle = vehicleDB.get(vehicleID);
    if (vehicle == null) {
      waiting.remove(index);
      return WaitlistDecision.continueFrom(index);
    }
    DateRange range =
        parseDateRangeForWaitlist("processWaitList", candidate.startDate, candidate.endDate);
    if (range.failed() || hasExistingReservation(candidate.customerID, vehicleID)) {
      waiting.remove(index);
      return WaitlistDecision.continueFrom(index);
    }
    String vehicleOffice = ServerIdRules.extractOfficeID(vehicleID);
    boolean slotAcquired = false;
    if (isCrossOffice(candidate.customerID, vehicleID)) {
      if (!acquireRemoteOfficeSlot(candidate.customerID, vehicleOffice)) {
        return WaitlistDecision.continueFrom(index + 1);
      }
      slotAcquired = true;
    }
    ReservationAssignment assignment =
        assignReservationIfEligible(
            candidate.customerID,
            vehicleID,
            candidate.startDate,
            candidate.endDate,
            range.start,
            range.end,
            vehicle,
            true,
            null);
    if (assignment.success) {
      waiting.remove(index);
      log("Waitlist auto-assign: " + candidate.customerID + " -> " + vehicleID);
      return WaitlistDecision.assignedOne();
    }
    if (slotAcquired) {
      releaseRemoteOfficeSlot(candidate.customerID, vehicleOffice);
    }
    return WaitlistDecision.continueFrom(index + 1);
  }
  // ==================== HELPER: Waitlist Processing ====================

  /** Callers must pass an already-normalized vehicle type (e.g. "Sedan", "SUV", "Truck"). */
  private String findVehicleLocal(String normalizedType) {
    StringBuilder result = new StringBuilder();
    LocalDate today = LocalDate.now();
    for (Vehicle vehicle : vehicleDB.values()) {
      if (!vehicle.getVehicleType().equals(normalizedType)) {
        continue;
      }
      String status = resolveAvailabilityStatus(vehicle.getVehicleID(), today);
      appendLine(result, formatFindVehicleLine(vehicle, status));
    }
    return result.toString().trim();
  }
  // ==================== HELPER: Local Search/List ====================

  private String listCustomerReservationsLocal(String customerID) {
    StringBuilder result = new StringBuilder();
    // Active reservations
    for (Map.Entry<String, List<Reservation>> entry : reservations.entrySet()) {
      String vehicleID = entry.getKey();
      List<Reservation> reservationList = entry.getValue();
      synchronized (reservationList) {
        for (Reservation reservation : reservationList) {
          if (!reservation.getCustomerID().equals(customerID)) {
            continue;
          }
          appendLine(result, formatReservationLine(vehicleID, reservation));
        }
      }
    }
    // Waitlist entries
    for (Map.Entry<String, List<WaitlistEntry>> entry : waitList.entrySet()) {
      String vehicleID = entry.getKey();
      Vehicle vehicle = vehicleDB.get(vehicleID);
      double price = vehicle != null ? vehicle.getReservationPrice() : 0;
      List<WaitlistEntry> entries = entry.getValue();
      synchronized (entries) {
        for (WaitlistEntry we : entries) {
          if (!we.customerID.equals(customerID)) {
            continue;
          }
          appendLine(result, formatWaitlistLine(vehicleID, we, price));
        }
      }
    }
    return result.toString();
  }

  private String resolveAvailabilityStatus(String vehicleID, LocalDate date) {
    return withVehicleLockResult(
        vehicleID, () -> hasCapacity(vehicleID, date, date, null) ? "Available" : "Reserved");
  }

  private String formatAvailableVehicleLine(Vehicle vehicle, String status) {
    return vehicle.getVehicleID()
        + " "
        + vehicle.getVehicleType()
        + " "
        + vehicle.getLicensePlate()
        + " "
        + vehicle.getReservationPrice()
        + " "
        + status;
  }

  private String formatFindVehicleLine(Vehicle vehicle, String status) {
    return vehicle.getVehicleID()
        + " "
        + vehicle.getVehicleType()
        + " "
        + status
        + " "
        + vehicle.getReservationPrice();
  }

  private String formatReservationLine(String vehicleID, Reservation reservation) {
    return vehicleID
        + " "
        + reservation.getStartDate()
        + " "
        + reservation.getEndDate()
        + " Price:"
        + resolveRefundAmount(reservation, 0, vehicleID);
  }

  private String formatWaitlistLine(String vehicleID, WaitlistEntry waitlistEntry, double price) {
    return vehicleID
        + " "
        + waitlistEntry.startDate
        + " "
        + waitlistEntry.endDate
        + " Price(Current):"
        + price
        + " [WAITLIST]";
  }

  // ==================== HELPER: Normalization ====================
  private String normalizeVehicleType(String vehicleType) {
    if (vehicleType == null) return null;
    return VALID_VEHICLE_TYPES.get(vehicleType.trim().toUpperCase());
  }

  /**
   * Send a UDP request to another server and wait for response. Uses in-process fast path if target
   * server is in same JVM.
   */
  private String sendUDPRequest(String targetServer, String request) {
    if (targetServer == null || !UDP_PORTS.containsKey(targetServer)) {
      String msg = String.format(MSG_UNKNOWN_OFFICE, targetServer);
      log("UDP Error - " + msg);
      return msg;
    }
    // Fast path: direct method call if server is registered in-process
    VehicleReservationWS target = serverRegistry.get(targetServer);
    if (target != null && target != this) {
      return target.handleUDPRequest(request);
    }
    // Network path: actual UDP socket
    int targetPort = UDP_PORTS.get(targetServer);
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.setSoTimeout(5000); // 5 second timeout
      byte[] sendData = request.getBytes(StandardCharsets.UTF_8);
      InetAddress address = InetAddress.getByName("localhost");
      DatagramPacket sendPacket =
          new DatagramPacket(sendData, sendData.length, address, targetPort);
      socket.send(sendPacket);
      byte[] receiveData = new byte[8192];
      DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
      socket.receive(receivePacket);
      return new String(receivePacket.getData(), 0, receivePacket.getLength(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      log("UDP Error to " + targetServer + ": " + e.getMessage());
      return "FAIL: Unable to contact " + targetServer + " server";
    }
  }
  // ==================== HELPER: UDP Communication ====================

  /** Extract price from a response string like "SUCCESS: ... Cost: 100.0 ..." */
  private double extractPrice(String response) {
    return RemoteResponseRules.extractPrice(response, this::log);
  }

  private RemoteResponseType classifyResponse(String response) {
    return RemoteResponseRules.classifyResponse(response);
  }

  private String releaseRemoteSlotAndReturn(String customerID, String officeID, String response) {
    releaseRemoteOfficeSlot(customerID, officeID);
    return response;
  }

  private boolean rollbackRemoteReservationAndReleaseSlot(
      String customerID, String vehicleID, String targetServer) {
    if (!rollbackRemoteReservation(customerID, vehicleID, targetServer)) {
      return false;
    }
    releaseRemoteOfficeSlot(customerID, targetServer);
    return true;
  }

  private String aggregateLocalAndRemoteResponses(String localResponse, String remoteRequest) {
    StringBuilder result = new StringBuilder();
    appendNonFailureResponse(result, localResponse);
    for (String office : UDP_PORTS.keySet()) {
      if (office.equals(serverID)) {
        continue;
      }
      appendNonFailureResponse(result, sendUDPRequest(office, remoteRequest));
    }
    return result.toString().trim();
  }

  private String appendNewBudgetInfo(String response, double newBudget) {
    return RemoteResponseRules.appendNewBudgetInfo(response, newBudget);
  }

  private void appendNonFailureResponse(StringBuilder result, String response) {
    if (response == null
        || response.trim().isEmpty()
        || classifyResponse(response) == RemoteResponseType.FAIL) {
      return;
    }
    appendLine(result, response.trim());
  }

  private String executeRemoteCustomerOperation(
      String operation,
      String customerID,
      String vehicleID,
      String targetServer,
      String request,
      RemoteOperationPreCheck preCheck,
      RemoteOperationPostProcessor postProcessor) {
    return withCustomerLockResult(
        customerID,
        () -> {
          if (preCheck != null) {
            String preCheckFailure = preCheck.run();
            if (preCheckFailure != null) {
              log(
                  operation
                      + " - Customer: "
                      + customerID
                      + ", Vehicle: "
                      + vehicleID
                      + " - "
                      + preCheckFailure);
              return preCheckFailure;
            }
          }
          String response = sendUDPRequest(targetServer, request);
          String finalResponse = postProcessor.run(response, classifyResponse(response));
          log(
              operation
                  + " - Customer: "
                  + customerID
                  + ", Vehicle: "
                  + vehicleID
                  + " - "
                  + finalResponse);
          return finalResponse;
        });
  }

  private List<Reservation> reservationListForVehicle(String vehicleID) {
    return reservations.computeIfAbsent(vehicleID, k -> Collections.synchronizedList(new ArrayList<>()));
  }

  private List<WaitlistEntry> waitlistForVehicle(String vehicleID) {
    return waitList.computeIfAbsent(vehicleID, k -> Collections.synchronizedList(new ArrayList<>()));
  }

  private void appendLine(StringBuilder builder, String line) {
    if (builder.length() > 0) {
      builder.append("\n");
    }
    builder.append(line);
  }

  private <T> T withCustomerLockResult(String customerID, LockedSection<T> section) {
    synchronized (customerLock(customerID)) {
      return section.run();
    }
  }

  private <T> T withVehicleLockResult(String vehicleID, LockedSection<T> section) {
    synchronized (vehicleLock(vehicleID)) {
      return section.run();
    }
  }

  private <T> T withCustomerThenVehicleLockResult(
      String customerID, String vehicleID, LockedSection<T> section) {
    return withCustomerLockResult(
        customerID, () -> withVehicleLockResult(vehicleID, section));
  }

  private Object vehicleLock(String vehicleID) {
    return vehicleLocks.computeIfAbsent(vehicleID, k -> new Object());
  }

  private Object customerLock(String customerID) {
    return customerLocks.computeIfAbsent(customerID, k -> new Object());
  }

  // ==================== LOGGING ====================
  private void log(String message) {
    String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    String logEntry = "[" + timestamp + "] [" + serverID + "] " + message;
    System.out.println(logEntry);
    File logDir = new File("logs");
    if (!logDir.exists()) logDir.mkdirs();
    try (FileWriter fw = new FileWriter(new File(logDir, serverID + "_server.log"), true);
        PrintWriter pw = new PrintWriter(fw)) {
      pw.println(logEntry);
    } catch (IOException e) {
      System.err.println("Error writing to log: " + e.getMessage());
    }
  }
  // ==================== HELPER: Waitlist Entry Record ====================

  // ==================== ACCESSORS (for UDPServer, tests) ====================
  String getServerID() {
    return serverID;
  }

  int getNextExpectedSeq() {
    return nextExpectedSeq;
  }

  synchronized void syncNextExpectedSeq(int nextExpectedSeq) {
    this.nextExpectedSeq = nextExpectedSeq;
    holdbackQueue.clear();
  }

  synchronized String executeCommittedSequence(
      int seqNum, String reqID, String feHost, int fePort, String operation) {
    this.nextExpectedSeq = seqNum;
    holdbackQueue.clear();
    return executeAndDeliver(seqNum, reqID, feHost, fePort, operation);
  }

  void resetLocalStateForTests() {
    vehicleDB.clear();
    waitList.clear();
    reservations.clear();
    vehicleLocks.clear();
  }

  private static final class DateRange {
    final LocalDate start;
    final LocalDate end;
    final String error; // non-null when parsing failed
    DateRange(LocalDate start, LocalDate end) {
      this.start = start;
      this.end = end;
      this.error = null;
    }
    private DateRange(String error) {
      this.start = null;
      this.end = null;
      this.error = error;
    }

    static DateRange fail(String error) {
      return new DateRange(error);
    }

    boolean failed() {
      return error != null;
    }
  }

  private static final class CustomerVehicleValidation {
    final String vehicleServer;
    final String error;

    private CustomerVehicleValidation(String vehicleServer, String error) {
      this.vehicleServer = vehicleServer;
      this.error = error;
    }

    static CustomerVehicleValidation ok(String vehicleServer) {
      return new CustomerVehicleValidation(vehicleServer, null);
    }

    static CustomerVehicleValidation fail(String error) {
      return new CustomerVehicleValidation(null, error);
    }

    boolean failed() {
      return error != null;
    }
  }

  @FunctionalInterface
  private interface LockedSection<T> {
    T run();
  }

  @FunctionalInterface
  private interface RemoteOperationPreCheck {
    String run();
  }

  @FunctionalInterface
  private interface RemoteOperationPostProcessor {
    String run(String response, RemoteResponseType responseType);
  }

  private static final class ActiveUpdateResult {
    final boolean updated;
    final String successMessage;
    final boolean shouldRunCleanup;
    final String failureMessage;

    private ActiveUpdateResult(
        boolean updated, String successMessage, boolean shouldRunCleanup, String failureMessage) {
      this.updated = updated;
      this.successMessage = successMessage;
      this.shouldRunCleanup = shouldRunCleanup;
      this.failureMessage = failureMessage;
    }

    static ActiveUpdateResult notFound() {
      return new ActiveUpdateResult(false, null, false, null);
    }

    static ActiveUpdateResult updated(String successMessage, boolean shouldRunCleanup) {
      return new ActiveUpdateResult(true, successMessage, shouldRunCleanup, null);
    }

    static ActiveUpdateResult fail(String failureMessage) {
      return new ActiveUpdateResult(false, null, false, failureMessage);
    }

    boolean failed() {
      return failureMessage != null;
    }
  }

  private static final class WaitlistDecision {
    final int nextIndex;
    final int assignedDelta;
    final boolean stopProcessing;

    private WaitlistDecision(int nextIndex, int assignedDelta, boolean stopProcessing) {
      this.nextIndex = nextIndex;
      this.assignedDelta = assignedDelta;
      this.stopProcessing = stopProcessing;
    }

    static WaitlistDecision continueFrom(int nextIndex) {
      return new WaitlistDecision(nextIndex, 0, false);
    }

    static WaitlistDecision assignedOne() {
      return new WaitlistDecision(-1, 1, true);
    }

    static WaitlistDecision stopNoAssignment() {
      return new WaitlistDecision(-1, 0, true);
    }
  }

  private static final class ReservationAssignment {
    final boolean success;
    final String failureMessage;
    final double price;

    private ReservationAssignment(boolean success, String failureMessage, double price) {
      this.success = success;
      this.failureMessage = failureMessage;
      this.price = price;
    }

    static ReservationAssignment success(double price) {
      return new ReservationAssignment(true, null, price);
    }

    static ReservationAssignment fail(String message) {
      return new ReservationAssignment(false, message, 0);
    }
  }

  // ==================== P2: HOLDBACK QUEUE + TOTAL ORDER ====================

  private static class PendingExecute {
    final int seqNum;
    final String reqID;
    final String feHost;
    final int fePort;
    final String operation;

    PendingExecute(int seqNum, String reqID, String feHost, int fePort, String operation) {
      this.seqNum = seqNum;
      this.reqID = reqID;
      this.feHost = feHost;
      this.fePort = fePort;
      this.operation = operation;
    }
  }

  synchronized String handleExecute(int seqNum, String reqID,
                                     String feHost, int fePort, String operation) {
    if (seqNum < nextExpectedSeq) {
      return "ACK:" + reqID;
    }
    if (seqNum > nextExpectedSeq) {
      holdbackQueue.add(new PendingExecute(seqNum, reqID, feHost, fePort, operation));
      return "NACK:" + serverID + ":" + nextExpectedSeq + ":" + (seqNum - 1);
    }
    String result = executeAndDeliver(seqNum, reqID, feHost, fePort, operation);
    while (!holdbackQueue.isEmpty() && holdbackQueue.peek().seqNum == nextExpectedSeq) {
      PendingExecute next = holdbackQueue.poll();
      executeAndDeliver(next.seqNum, next.reqID, next.feHost, next.fePort, next.operation);
    }
    return result;
  }

  private String executeAndDeliver(int seqNum, String reqID,
                                    String feHost, int fePort, String operation) {
    String resultReplicaId =
        (replicaIDForResult != null && !replicaIDForResult.isEmpty())
            ? replicaIDForResult
            : serverID;
    if (byzantineMode) {
      nextExpectedSeq++;
      String resultMsg = "RESULT:" + seqNum + ":" + reqID + ":" + resultReplicaId
          + ":BYZANTINE_RANDOM_" + System.nanoTime();
      sendResultToFE(feHost, fePort, resultMsg);
      return resultMsg;
    }
    String result = handleUDPRequest(operation);
    nextExpectedSeq++;
    String resultMsg = "RESULT:" + seqNum + ":" + reqID + ":" + resultReplicaId + ":" + result;
    sendResultToFE(feHost, fePort, resultMsg);
    return resultMsg;
  }

  private void sendResultToFE(String feHost, int fePort, String resultMsg) {
    Thread resultSender =
        new Thread(
            () -> {
              try (DatagramSocket socket = new DatagramSocket()) {
                boolean acked =
                    reliableSender.send(resultMsg, InetAddress.getByName(feHost), fePort, socket);
                if (!acked) {
                  System.err.println(serverID + ": RESULT not ACKed by FE");
                }
              } catch (Exception e) {
                System.err.println(serverID + ": Failed to send result to FE: " + e.getMessage());
              }
            },
            serverID + "-ResultSender");
    resultSender.setDaemon(true);
    resultSender.start();
  }

  // ==================== P2: STATE SNAPSHOT ====================

  @SuppressWarnings("unchecked")
  public String getStateSnapshot() {
    try {
      Map<String, Object> state = new HashMap<>();
      state.put("vehicleDB", new HashMap<>(vehicleDB));
      state.put("reservations", deepCopyReservations());
      state.put("waitList", deepCopyWaitList());
      state.put("customerBudget", new HashMap<>(customerBudget));
      state.put("crossOfficeCount", deepCopyCrossOffice());
      state.put("nextExpectedSeq", nextExpectedSeq);

      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      ObjectOutputStream oos = new ObjectOutputStream(bos);
      oos.writeObject(state);
      oos.close();
      return Base64.getEncoder().encodeToString(bos.toByteArray());
    } catch (IOException e) {
      throw new RuntimeException("Snapshot serialization failed", e);
    }
  }

  @SuppressWarnings("unchecked")
  public void loadStateSnapshot(String snapshot) {
    try {
      byte[] data = Base64.getDecoder().decode(snapshot);
      ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data));
      Map<String, Object> state = (Map<String, Object>) ois.readObject();
      ois.close();

      vehicleDB.clear();
      vehicleDB.putAll((Map<String, Vehicle>) state.get("vehicleDB"));
      reservations.clear();
      reservations.putAll((Map<String, List<Reservation>>) state.get("reservations"));
      waitList.clear();
      waitList.putAll((Map<String, List<WaitlistEntry>>) state.get("waitList"));
      customerBudget.clear();
      customerBudget.putAll((Map<String, Double>) state.get("customerBudget"));
      crossOfficeCount.clear();
      crossOfficeCount.putAll((Map<String, Set<String>>) state.get("crossOfficeCount"));
      nextExpectedSeq = (int) state.get("nextExpectedSeq");
    } catch (Exception e) {
      throw new RuntimeException("Snapshot loading failed", e);
    }
  }

  private Map<String, List<Reservation>> deepCopyReservations() {
    Map<String, List<Reservation>> copy = new HashMap<>();
    for (Map.Entry<String, List<Reservation>> entry : reservations.entrySet()) {
      copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
    }
    return copy;
  }

  private Map<String, List<WaitlistEntry>> deepCopyWaitList() {
    Map<String, List<WaitlistEntry>> copy = new HashMap<>();
    for (Map.Entry<String, List<WaitlistEntry>> entry : waitList.entrySet()) {
      copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
    }
    return copy;
  }

  private Map<String, Set<String>> deepCopyCrossOffice() {
    Map<String, Set<String>> copy = new HashMap<>();
    for (Map.Entry<String, Set<String>> entry : crossOfficeCount.entrySet()) {
      copy.put(entry.getKey(), new HashSet<>(entry.getValue()));
    }
    return copy;
  }

  /** Encapsulates atomic budget operations using ConcurrentHashMap.compute(). */
  private static class BudgetManager {
    double get(String id) {
      return customerBudget.computeIfAbsent(id, k -> DEFAULT_BUDGET);
    }
    boolean canAfford(String id, double amount) {
      return get(id) >= amount;
    }
    void deduct(String id, double amount) {
      customerBudget.compute(
          id, (k, current) -> (current == null ? DEFAULT_BUDGET : current) - amount);
    }
    void refund(String id, double amount) {
      customerBudget.compute(
          id, (k, current) -> (current == null ? DEFAULT_BUDGET : current) + amount);
    }
  }

}
