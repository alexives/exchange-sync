package com.zerodes.exchangesync.tasksource.rtm;

public class RtmServerException extends Exception {
	private static final long serialVersionUID = 1L;
	
	private final int rtmErrorCode;
	private final String rtmMessage;
	
	public RtmServerException(final int rtmErrorCode, final String rtmMessage) {
		super("Error " + String.valueOf(rtmErrorCode) + ": " + rtmMessage);
		this.rtmErrorCode = rtmErrorCode;
		this.rtmMessage = rtmMessage;
	}

	public String getRtmMessage() {
		return rtmMessage;
	}

	public int getRtmErrorCode() {
		return rtmErrorCode;
	}
}
