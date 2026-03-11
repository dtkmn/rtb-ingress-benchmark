package demo.adtech;

class PublisherUnavailableException extends RuntimeException {

    PublisherUnavailableException(Throwable cause) {
        super(cause);
    }
}

class PublisherBackpressureException extends RuntimeException {

    PublisherBackpressureException(Throwable cause) {
        super(cause);
    }
}

