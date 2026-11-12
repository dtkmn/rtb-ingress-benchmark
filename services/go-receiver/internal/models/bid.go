package models

// BidRequest is the closed benchmark v1 subset, after strict input validation.
// Pointers distinguish omitted/null optional members from supplied empty values.
type BidRequest struct {
	ID     string  `json:"id"`
	Site   *Site   `json:"site,omitempty"`
	App    *App    `json:"app,omitempty"`
	Device *Device `json:"device"`
	User   *User   `json:"user,omitempty"`
}

type Site struct {
	ID     *string `json:"id,omitempty"`
	Domain *string `json:"domain,omitempty"`
}
type App struct {
	ID     *string `json:"id,omitempty"`
	Bundle *string `json:"bundle,omitempty"`
}
type Device struct {
	IP  *string `json:"ip,omitempty"`
	OS  *string `json:"os,omitempty"`
	UA  *string `json:"ua,omitempty"`
	LMT int     `json:"lmt"` // Always present, including the default zero.
}
type User struct {
	ID *string `json:"id,omitempty"`
}
