package github.lms.lemuel.operation.site.application.port.out;

import github.lms.lemuel.operation.site.domain.Popup;

/** 팝업 저장 포트. */
@FunctionalInterface
public interface SavePopupPort {
    Popup save(Popup popup);
}
