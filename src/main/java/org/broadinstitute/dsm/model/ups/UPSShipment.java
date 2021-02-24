package org.broadinstitute.dsm.model.ups;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

@Data
public class UPSShipment {
    @SerializedName ("package")
    UPSPackage[] upsPackageArray;
}

class UPSLocation {
    UPSAddress address;
}
class UPSAddress{
    String city;
    String stateProvince;
    String postalCode;
    String countryCode;
}

