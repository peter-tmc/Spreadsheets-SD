package tp1.api;

public class GoogleSheetsReturn {
    private String range;
    private String majorDimension;
    private String[][] values;

    public GoogleSheetsReturn() {
    }

    public String getRange() {
        return range;
    }

    public String getMajorDimension() {
        return majorDimension;
    }

    public String[][] getValues() {
		return values;
	}

	public void setRange(String range) {
		this.range = range;
	}

	public void setMajorDimension(String majorDimension) {
		this.majorDimension=majorDimension;
	}

	public void setValues(String[][] values) {
		this.values=values;
	}
}
