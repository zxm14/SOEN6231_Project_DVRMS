
package client.generated;

import javax.xml.bind.JAXBElement;
import javax.xml.bind.annotation.XmlElementDecl;
import javax.xml.bind.annotation.XmlRegistry;
import javax.xml.namespace.QName;


/**
 * This object contains factory methods for each 
 * Java content interface and Java element interface 
 * generated in the client.generated package. 
 * <p>An ObjectFactory allows you to programatically 
 * construct new instances of the Java representation 
 * for XML content. The Java representation of XML 
 * content can consist of schema derived interfaces 
 * and classes representing the binding of schema 
 * type definitions, element declarations and model 
 * groups.  Factory methods for each of these are 
 * provided in this class.
 * 
 */
@XmlRegistry
public class ObjectFactory {

    private final static QName _ListAvailableVehicleResponse_QNAME = new QName("http://server/", "listAvailableVehicleResponse");
    private final static QName _ListCustomerReservationsResponse_QNAME = new QName("http://server/", "listCustomerReservationsResponse");
    private final static QName _RemoveVehicle_QNAME = new QName("http://server/", "removeVehicle");
    private final static QName _AddVehicle_QNAME = new QName("http://server/", "addVehicle");
    private final static QName _AddVehicleResponse_QNAME = new QName("http://server/", "addVehicleResponse");
    private final static QName _CancelReservationResponse_QNAME = new QName("http://server/", "cancelReservationResponse");
    private final static QName _FindVehicleResponse_QNAME = new QName("http://server/", "findVehicleResponse");
    private final static QName _FindVehicle_QNAME = new QName("http://server/", "findVehicle");
    private final static QName _ListAvailableVehicle_QNAME = new QName("http://server/", "listAvailableVehicle");
    private final static QName _UpdateReservation_QNAME = new QName("http://server/", "updateReservation");
    private final static QName _UpdateReservationResponse_QNAME = new QName("http://server/", "updateReservationResponse");
    private final static QName _ListCustomerReservations_QNAME = new QName("http://server/", "listCustomerReservations");
    private final static QName _RemoveVehicleResponse_QNAME = new QName("http://server/", "removeVehicleResponse");
    private final static QName _ReserveVehicle_QNAME = new QName("http://server/", "reserveVehicle");
    private final static QName _ReserveVehicleResponse_QNAME = new QName("http://server/", "reserveVehicleResponse");
    private final static QName _AddToWaitListResponse_QNAME = new QName("http://server/", "addToWaitListResponse");
    private final static QName _CancelReservation_QNAME = new QName("http://server/", "cancelReservation");
    private final static QName _AddToWaitList_QNAME = new QName("http://server/", "addToWaitList");

    /**
     * Create a new ObjectFactory that can be used to create new instances of schema derived classes for package: client.generated
     * 
     */
    public ObjectFactory() {
    }

    /**
     * Create an instance of {@link RemoveVehicleResponse }
     * 
     */
    public RemoveVehicleResponse createRemoveVehicleResponse() {
        return new RemoveVehicleResponse();
    }

    /**
     * Create an instance of {@link UpdateReservationResponse }
     * 
     */
    public UpdateReservationResponse createUpdateReservationResponse() {
        return new UpdateReservationResponse();
    }

    /**
     * Create an instance of {@link ListCustomerReservations }
     * 
     */
    public ListCustomerReservations createListCustomerReservations() {
        return new ListCustomerReservations();
    }

    /**
     * Create an instance of {@link FindVehicle }
     * 
     */
    public FindVehicle createFindVehicle() {
        return new FindVehicle();
    }

    /**
     * Create an instance of {@link ListAvailableVehicle }
     * 
     */
    public ListAvailableVehicle createListAvailableVehicle() {
        return new ListAvailableVehicle();
    }

    /**
     * Create an instance of {@link UpdateReservation }
     * 
     */
    public UpdateReservation createUpdateReservation() {
        return new UpdateReservation();
    }

    /**
     * Create an instance of {@link CancelReservationResponse }
     * 
     */
    public CancelReservationResponse createCancelReservationResponse() {
        return new CancelReservationResponse();
    }

    /**
     * Create an instance of {@link FindVehicleResponse }
     * 
     */
    public FindVehicleResponse createFindVehicleResponse() {
        return new FindVehicleResponse();
    }

    /**
     * Create an instance of {@link AddVehicle }
     * 
     */
    public AddVehicle createAddVehicle() {
        return new AddVehicle();
    }

    /**
     * Create an instance of {@link AddVehicleResponse }
     * 
     */
    public AddVehicleResponse createAddVehicleResponse() {
        return new AddVehicleResponse();
    }

    /**
     * Create an instance of {@link RemoveVehicle }
     * 
     */
    public RemoveVehicle createRemoveVehicle() {
        return new RemoveVehicle();
    }

    /**
     * Create an instance of {@link ListAvailableVehicleResponse }
     * 
     */
    public ListAvailableVehicleResponse createListAvailableVehicleResponse() {
        return new ListAvailableVehicleResponse();
    }

    /**
     * Create an instance of {@link ListCustomerReservationsResponse }
     * 
     */
    public ListCustomerReservationsResponse createListCustomerReservationsResponse() {
        return new ListCustomerReservationsResponse();
    }

    /**
     * Create an instance of {@link AddToWaitList }
     * 
     */
    public AddToWaitList createAddToWaitList() {
        return new AddToWaitList();
    }

    /**
     * Create an instance of {@link CancelReservation }
     * 
     */
    public CancelReservation createCancelReservation() {
        return new CancelReservation();
    }

    /**
     * Create an instance of {@link AddToWaitListResponse }
     * 
     */
    public AddToWaitListResponse createAddToWaitListResponse() {
        return new AddToWaitListResponse();
    }

    /**
     * Create an instance of {@link ReserveVehicleResponse }
     * 
     */
    public ReserveVehicleResponse createReserveVehicleResponse() {
        return new ReserveVehicleResponse();
    }

    /**
     * Create an instance of {@link ReserveVehicle }
     * 
     */
    public ReserveVehicle createReserveVehicle() {
        return new ReserveVehicle();
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link ListAvailableVehicleResponse }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://server/", name = "listAvailableVehicleResponse")
    public JAXBElement<ListAvailableVehicleResponse> createListAvailableVehicleResponse(ListAvailableVehicleResponse value) {
        return new JAXBElement<ListAvailableVehicleResponse>(_ListAvailableVehicleResponse_QNAME, ListAvailableVehicleResponse.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link ListCustomerReservationsResponse }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://server/", name = "listCustomerReservationsResponse")
    public JAXBElement<ListCustomerReservationsResponse> createListCustomerReservationsResponse(ListCustomerReservationsResponse value) {
        return new JAXBElement<ListCustomerReservationsResponse>(_ListCustomerReservationsResponse_QNAME, ListCustomerReservationsResponse.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link RemoveVehicle }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://server/", name = "removeVehicle")
    public JAXBElement<RemoveVehicle> createRemoveVehicle(RemoveVehicle value) {
        return new JAXBElement<RemoveVehicle>(_RemoveVehicle_QNAME, RemoveVehicle.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link AddVehicle }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://server/", name = "addVehicle")
    public JAXBElement<AddVehicle> createAddVehicle(AddVehicle value) {
        return new JAXBElement<AddVehicle>(_AddVehicle_QNAME, AddVehicle.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link AddVehicleResponse }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://server/", name = "addVehicleResponse")
    public JAXBElement<AddVehicleResponse> createAddVehicleResponse(AddVehicleResponse value) {
        return new JAXBElement<AddVehicleResponse>(_AddVehicleResponse_QNAME, AddVehicleResponse.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link CancelReservationResponse }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://server/", name = "cancelReservationResponse")
    public JAXBElement<CancelReservationResponse> createCancelReservationResponse(CancelReservationResponse value) {
        return new JAXBElement<CancelReservationResponse>(_CancelReservationResponse_QNAME, CancelReservationResponse.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link FindVehicleResponse }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://server/", name = "findVehicleResponse")
    public JAXBElement<FindVehicleResponse> createFindVehicleResponse(FindVehicleResponse value) {
        return new JAXBElement<FindVehicleResponse>(_FindVehicleResponse_QNAME, FindVehicleResponse.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link FindVehicle }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://server/", name = "findVehicle")
    public JAXBElement<FindVehicle> createFindVehicle(FindVehicle value) {
        return new JAXBElement<FindVehicle>(_FindVehicle_QNAME, FindVehicle.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link ListAvailableVehicle }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://server/", name = "listAvailableVehicle")
    public JAXBElement<ListAvailableVehicle> createListAvailableVehicle(ListAvailableVehicle value) {
        return new JAXBElement<ListAvailableVehicle>(_ListAvailableVehicle_QNAME, ListAvailableVehicle.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link UpdateReservation }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://server/", name = "updateReservation")
    public JAXBElement<UpdateReservation> createUpdateReservation(UpdateReservation value) {
        return new JAXBElement<UpdateReservation>(_UpdateReservation_QNAME, UpdateReservation.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link UpdateReservationResponse }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://server/", name = "updateReservationResponse")
    public JAXBElement<UpdateReservationResponse> createUpdateReservationResponse(UpdateReservationResponse value) {
        return new JAXBElement<UpdateReservationResponse>(_UpdateReservationResponse_QNAME, UpdateReservationResponse.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link ListCustomerReservations }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://server/", name = "listCustomerReservations")
    public JAXBElement<ListCustomerReservations> createListCustomerReservations(ListCustomerReservations value) {
        return new JAXBElement<ListCustomerReservations>(_ListCustomerReservations_QNAME, ListCustomerReservations.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link RemoveVehicleResponse }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://server/", name = "removeVehicleResponse")
    public JAXBElement<RemoveVehicleResponse> createRemoveVehicleResponse(RemoveVehicleResponse value) {
        return new JAXBElement<RemoveVehicleResponse>(_RemoveVehicleResponse_QNAME, RemoveVehicleResponse.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link ReserveVehicle }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://server/", name = "reserveVehicle")
    public JAXBElement<ReserveVehicle> createReserveVehicle(ReserveVehicle value) {
        return new JAXBElement<ReserveVehicle>(_ReserveVehicle_QNAME, ReserveVehicle.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link ReserveVehicleResponse }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://server/", name = "reserveVehicleResponse")
    public JAXBElement<ReserveVehicleResponse> createReserveVehicleResponse(ReserveVehicleResponse value) {
        return new JAXBElement<ReserveVehicleResponse>(_ReserveVehicleResponse_QNAME, ReserveVehicleResponse.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link AddToWaitListResponse }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://server/", name = "addToWaitListResponse")
    public JAXBElement<AddToWaitListResponse> createAddToWaitListResponse(AddToWaitListResponse value) {
        return new JAXBElement<AddToWaitListResponse>(_AddToWaitListResponse_QNAME, AddToWaitListResponse.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link CancelReservation }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://server/", name = "cancelReservation")
    public JAXBElement<CancelReservation> createCancelReservation(CancelReservation value) {
        return new JAXBElement<CancelReservation>(_CancelReservation_QNAME, CancelReservation.class, null, value);
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link AddToWaitList }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://server/", name = "addToWaitList")
    public JAXBElement<AddToWaitList> createAddToWaitList(AddToWaitList value) {
        return new JAXBElement<AddToWaitList>(_AddToWaitList_QNAME, AddToWaitList.class, null, value);
    }

}
