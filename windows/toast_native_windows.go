//go:build windows

package main

import (
	"errors"
	"syscall"
	"unsafe"

	"git.sr.ht/~jackmordaunt/go-toast/v2/wintoast"
	"github.com/go-ole/go-ole"
	"golang.org/x/sys/windows"
)

// go-toast supplies activation/registration but does not expose Tag, Group or
// SuppressPopup. These small WinRT bindings supply those properties without a
// PowerShell process or a fork of the dependency. ABI source: Microsoft's
// windows-rs Windows/UI/Notifications and Windows/Data/Xml/Dom bindings.
// The Tag is per sender (smsToastTagFor); the Group stays fixed.
const smsToastGroup = "msgdock-sms"

var toastCombase = windows.NewLazySystemDLL("combase.dll")

type nativeToastSender struct {
	initialized bool
	cookie      uint32
}

func hresultError(hr uintptr) error {
	if int32(hr) < 0 {
		return ole.NewError(hr)
	}
	return nil // S_FALSE is also success.
}

// All referenced interfaces inherit IInspectable (six slots). Callers supply
// only slots documented for that exact interface, never a runtime input.
// Pointer arguments converted to uintptr must stay alive across this wrapper.
//
//go:uintptrescapes
func toastCOMCall(object *ole.IUnknown, slot int, args ...uintptr) error {
	vtable := unsafe.Slice((*uintptr)(unsafe.Pointer(object.RawVTable)), slot+1)
	params := append([]uintptr{uintptr(unsafe.Pointer(object))}, args...)
	hr, _, _ := syscall.SyscallN(vtable[slot], params...)
	return hresultError(hr)
}

func toastStringCall(object *ole.IUnknown, slot int, value string) error {
	h, err := ole.NewHString(value)
	if err != nil {
		return err
	}
	defer ole.DeleteHString(h)
	return toastCOMCall(object, slot, uintptr(h))
}

func (s *nativeToastSender) initialize() error {
	if s.initialized {
		return nil
	}
	hr, _, _ := toastCombase.NewProc("RoInitialize").Call(1)
	if err := hresultError(hr); err != nil {
		return err
	}
	guid := ole.NewGUID(notificationActivatorGUID)
	hr, _, _ = toastCombase.NewProc("CoRegisterClassObject").Call(
		uintptr(unsafe.Pointer(guid)), uintptr(unsafe.Pointer(wintoast.ClassFactory)),
		uintptr(ole.CLSCTX_LOCAL_SERVER), 1, uintptr(unsafe.Pointer(&s.cookie)))
	if err := hresultError(hr); err != nil {
		toastCombase.NewProc("RoUninitialize").Call()
		return err
	}
	s.initialized = true
	return nil
}

func (s *nativeToastSender) close() {
	if !s.initialized {
		return
	}
	toastCombase.NewProc("CoRevokeClassObject").Call(uintptr(s.cookie))
	toastCombase.NewProc("RoUninitialize").Call()
	s.initialized = false
}

func (s *nativeToastSender) push(xml, tag string, suppress bool) error {
	if err := s.initialize(); err != nil {
		return err
	}
	return pushTaggedToast(notificationAppID, xml, tag, suppress)
}

// Called only on the notification worker's locked MTA thread. Every factory,
// interface, HSTRING and initialization reference has a matching release.
func newTaggedToast(xml, tag string, suppress bool) (*ole.IUnknown, error) {
	doc, err := ole.RoActivateInstance("Windows.Data.Xml.Dom.XmlDocument")
	if err != nil {
		return nil, err
	}
	defer doc.Release()
	io, err := doc.QueryInterface(ole.NewGUID("6cd0e74e-ee65-4489-9ebf-ca43e87ba637"))
	if err != nil {
		return nil, err
	}
	defer io.Release()
	if err := toastStringCall(&io.IUnknown, 6, xml); err != nil {
		return nil, err
	}
	factory, err := ole.RoGetActivationFactory("Windows.UI.Notifications.ToastNotification",
		ole.NewGUID("04124b20-82c6-4229-b109-fd9ed4662b53"))
	if err != nil {
		return nil, err
	}
	defer factory.Release()
	var toast *ole.IUnknown
	if err := toastCOMCall(&factory.IUnknown, 6, uintptr(unsafe.Pointer(doc)), uintptr(unsafe.Pointer(&toast))); err != nil {
		return nil, err
	}
	if toast == nil {
		return nil, errors.New("WinRT returned no toast")
	}
	success := false
	defer func() {
		if !success {
			toast.Release()
		}
	}()
	properties, err := toast.QueryInterface(ole.NewGUID("9dfb9fd1-143a-490e-90bf-b9fba7132de7"))
	if err != nil {
		return nil, err
	}
	defer properties.Release()
	if err := toastStringCall(&properties.IUnknown, 6, tag); err != nil {
		return nil, err
	}
	if err := toastStringCall(&properties.IUnknown, 8, smsToastGroup); err != nil {
		return nil, err
	}
	var quiet uintptr
	if suppress {
		quiet = 1
	}
	if err := toastCOMCall(&properties.IUnknown, 10, quiet); err != nil {
		return nil, err
	}
	success = true
	return toast, nil
}

func pushTaggedToast(appID, xml, tag string, suppress bool) error {
	toast, err := newTaggedToast(xml, tag, suppress)
	if err != nil {
		return err
	}
	defer toast.Release()
	manager, err := ole.RoGetActivationFactory("Windows.UI.Notifications.ToastNotificationManager",
		ole.NewGUID("50ac103f-d235-4598-bbef-98fe4d1a3ad4"))
	if err != nil {
		return err
	}
	defer manager.Release()
	id, err := ole.NewHString(appID)
	if err != nil {
		return err
	}
	defer ole.DeleteHString(id)
	var notifier *ole.IUnknown
	if err := toastCOMCall(&manager.IUnknown, 7, uintptr(id), uintptr(unsafe.Pointer(&notifier))); err != nil {
		return err
	}
	if notifier == nil {
		return errors.New("WinRT returned no notifier")
	}
	defer notifier.Release()
	return toastCOMCall(notifier, 6, uintptr(unsafe.Pointer(toast)))
}
