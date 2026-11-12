package demo.adtech;

import io.quarkus.runtime.annotations.RegisterForReflection;

// The bounded early body reader replaces REST's inferred request-type registration.
@RegisterForReflection(targets = { BidRequest.class, BidRequest.Site.class, BidRequest.App.class,
        BidRequest.Device.class, BidRequest.User.class })
final class ReceiverModelReflection {}
