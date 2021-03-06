
package org.linlinjava.litemall.wx.web;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.bean.WxMaJscode2SessionResult;
import cn.binarywang.wx.miniapp.bean.WxMaPhoneNumberInfo;
import org.linlinjava.litemall.core.notify.NotifyService;
import org.linlinjava.litemall.core.notify.NotifyType;
import org.linlinjava.litemall.core.util.CharUtil;
import org.linlinjava.litemall.core.util.IpUtil;
import org.linlinjava.litemall.core.util.JacksonUtil;
import org.linlinjava.litemall.core.util.RegexUtil;
import org.linlinjava.litemall.core.util.ResponseUtil;
import org.linlinjava.litemall.core.util.bcrypt.BCryptPasswordEncoder;
import org.linlinjava.litemall.db.domain.LitemallUser;
import org.linlinjava.litemall.db.service.CouponAssignService;
import org.linlinjava.litemall.db.service.LitemallUserService;
import org.linlinjava.litemall.wx.annotation.LoginUser;
import org.linlinjava.litemall.wx.dto.UserInfo;
import org.linlinjava.litemall.wx.dto.WxLoginInfo;
import org.linlinjava.litemall.wx.model.wxpay.WxResponseCode;
import org.linlinjava.litemall.wx.service.CaptchaCodeManager;
import org.linlinjava.litemall.wx.service.UserTokenManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/wx/auth")
@Validated
public class WxAuthController {

	private final static String DEFAULT_USER_AVATAR_URL = "https://yanxuan.nosdn.127.net/80841d741d7fa3073e0ae27bf487339f.jpg?imageView&quality=90&thumbnail=64x64";

	@Autowired
	private LitemallUserService userService;

	@Autowired
	private WxMaService wxService;

	@Autowired
	private NotifyService notifyService;

	@Autowired
	private CouponAssignService couponAssignService;

	@PostMapping("login")
	public Object login(@RequestBody String body, HttpServletRequest request) {
//    	notifyService.notifyMail("email notify test", "email notify test successed!");
		String username = JacksonUtil.parseString(body, "username");
		String password = JacksonUtil.parseString(body, "password");
		if (username == null || password == null) {
			return ResponseUtil.badArgument();
		}
		List<LitemallUser> userList = userService.queryByUsername(username);
		LitemallUser user = null;
		if (userList.size() > 1) {
			return ResponseUtil.serious();
		} else if (userList.size() == 0) {
			return ResponseUtil.fail(WxResponseCode.AUTH_INVALID_ACCOUNT, "账号不存在");
		} else {
			user = userList.get(0);
		}
		BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
		if (!encoder.matches(password, user.getPassword())) {
			return ResponseUtil.fail(WxResponseCode.AUTH_INVALID_ACCOUNT, "账号密码不对");
		}
		user.setLastLoginTime(LocalDateTime.now());
		user.setLastLoginIp(IpUtil.getIpAddr(request));
		if (userService.updateById(user) == 0) {
			return ResponseUtil.updatedDataFailed();
		}
		UserInfo userInfo = new UserInfo();
		userInfo.setNickName(user.getNickname());
		userInfo.setAvatarUrl(user.getAvatar());
		String token = UserTokenManager.generateToken(user.getId());
		Map<Object, Object> result = new HashMap<Object, Object>();
		result.put("token", token);
		result.put("userInfo", userInfo);
		return ResponseUtil.ok(result);
	}

	@PostMapping("login_by_weixin")
	public Object loginByWeixin(@RequestBody WxLoginInfo wxLoginInfo, HttpServletRequest request) {
		String code = wxLoginInfo.getCode();
		UserInfo userInfo = wxLoginInfo.getUserInfo();
		if (code == null || userInfo == null) {
			return ResponseUtil.badArgument();
		}
		String sessionKey = null;
		String openId = null;
		try {
			WxMaJscode2SessionResult result = this.wxService.getUserService().getSessionInfo(code);
			sessionKey = result.getSessionKey();
			System.out.println("wx-sessionKey=" + sessionKey);
			openId = result.getOpenid();
			System.out.println("wx-openId=" + sessionKey);
		} catch (Exception e) {
			e.printStackTrace();
		}
		if (sessionKey == null || openId == null) {
			return ResponseUtil.fail();
		}
		LitemallUser user = userService.queryByOid(openId);
		if (user == null) {
			user = new LitemallUser();
			user.setUsername(openId);
			user.setPassword(openId);
			user.setWeixinOpenid(openId);
			user.setAvatar(userInfo.getAvatarUrl());
			user.setNickname(userInfo.getNickName());
			user.setGender(userInfo.getGender());
			user.setUserLevel((byte) 0);
			user.setStatus((byte) 0);
			user.setLastLoginTime(LocalDateTime.now());
			user.setLastLoginIp(IpUtil.getIpAddr(request));
			user.setSessionKey(sessionKey);
			userService.add(user);
			couponAssignService.assignForRegister(user.getId());
		} else {
			user.setLastLoginTime(LocalDateTime.now());
			user.setLastLoginIp(IpUtil.getIpAddr(request));
			user.setSessionKey(sessionKey);
			if (userService.updateById(user) == 0) {
				return ResponseUtil.updatedDataFailed();
			}
		}
		String token = UserTokenManager.generateToken(user.getId());
		Map<Object, Object> result = new HashMap<Object, Object>();
		result.put("token", token);
		result.put("userInfo", userInfo);
		return ResponseUtil.ok(result);
	}

	@PostMapping("regCaptcha")
	public Object registerCaptcha(@RequestBody String body) {
		String phoneNumber = JacksonUtil.parseString(body, "mobile");
		if (StringUtils.isEmpty(phoneNumber)) {
			return ResponseUtil.badArgument();
		}
		if (!RegexUtil.isMobileExact(phoneNumber)) {
			return ResponseUtil.badArgumentValue();
		}
		if (!notifyService.isSmsEnable()) {
			return ResponseUtil.fail(WxResponseCode.AUTH_CAPTCHA_UNSUPPORT, "小程序后台验证码服务不支持");
		}
		String code = CharUtil.getRandomNum(6);
		notifyService.notifySmsTemplate(phoneNumber, NotifyType.CAPTCHA, new String[] { code });
		boolean successful = CaptchaCodeManager.addToCache(phoneNumber, code);
		if (!successful) {
			return ResponseUtil.fail(WxResponseCode.AUTH_CAPTCHA_FREQUENCY, "验证码未超时1分钟，不能发送");
		}
		return ResponseUtil.ok();
	}

	@PostMapping("register")
	public Object register(@RequestBody String body, HttpServletRequest request) {
		String username = JacksonUtil.parseString(body, "username");
		String password = JacksonUtil.parseString(body, "password");
		String mobile = JacksonUtil.parseString(body, "mobile");
		String code = JacksonUtil.parseString(body, "code");
		String wxCode = JacksonUtil.parseString(body, "wxCode");
		if (StringUtils.isEmpty(username) 
			|| StringUtils.isEmpty(password) 
			|| StringUtils.isEmpty(mobile)
			|| StringUtils.isEmpty(wxCode) 
			|| StringUtils.isEmpty(code)) {
			return ResponseUtil.badArgument();
		}
		List<LitemallUser> userList = userService.queryByUsername(username);
		if (userList.size() > 0) {
			return ResponseUtil.fail(WxResponseCode.AUTH_NAME_REGISTERED, "用户名已注册");
		}
		userList = userService.queryByMobile(mobile);
		if (userList.size() > 0) {
			return ResponseUtil.fail(WxResponseCode.AUTH_MOBILE_REGISTERED, "手机号已注册");
		}
		if (!RegexUtil.isMobileExact(mobile)) {
			return ResponseUtil.fail(WxResponseCode.AUTH_INVALID_MOBILE, "手机号格式不正确");
		}
		String cacheCode = CaptchaCodeManager.getCachedCaptcha(mobile);
		if (cacheCode == null || cacheCode.isEmpty() || !cacheCode.equals(code)) {
			return ResponseUtil.fail(WxResponseCode.AUTH_CAPTCHA_UNMATCH, "验证码错误");
		}
		String openId = null;
		try {
			WxMaJscode2SessionResult result = this.wxService.getUserService().getSessionInfo(wxCode);
			openId = result.getOpenid();
		} catch (Exception e) {
			e.printStackTrace();
			return ResponseUtil.fail(WxResponseCode.AUTH_OPENID_UNACCESS, "openid 获取失败");
		}
		userList = userService.queryByOpenid(openId);
		if (userList.size() > 1) {
			return ResponseUtil.serious();
		}
		if (userList.size() == 1) {
			LitemallUser checkUser = userList.get(0);
			String checkUsername = checkUser.getUsername();
			String checkPassword = checkUser.getPassword();
			if (!checkUsername.equals(openId) || !checkPassword.equals(openId)) {
				return ResponseUtil.fail(WxResponseCode.AUTH_OPENID_BINDED, "openid已绑定账号");
			}
		}
		LitemallUser user = null;
		BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
		String encodedPassword = encoder.encode(password);
		user = new LitemallUser();
		user.setUsername(username);
		user.setPassword(encodedPassword);
		user.setMobile(mobile);
		user.setWeixinOpenid(openId);
		user.setAvatar(DEFAULT_USER_AVATAR_URL);
		user.setNickname(username);
		user.setGender((byte) 0);
		user.setUserLevel((byte) 0);
		user.setStatus((byte) 0);
		user.setLastLoginTime(LocalDateTime.now());
		user.setLastLoginIp(IpUtil.getIpAddr(request));
		userService.add(user);
		couponAssignService.assignForRegister(user.getId());
		// userInfo
		UserInfo userInfo = new UserInfo();
		userInfo.setNickName(username);
		userInfo.setAvatarUrl(user.getAvatar());
		// token
		String token = UserTokenManager.generateToken(user.getId());
		Map<Object, Object> result = new HashMap<Object, Object>();
		result.put("token", token);
		result.put("userInfo", userInfo);
		return ResponseUtil.ok(result);
	}

	@PostMapping("captcha")
	public Object captcha(@LoginUser Integer userId, @RequestBody String body) {
		if (userId == null) {
			return ResponseUtil.unlogin();
		}
		String phoneNumber = JacksonUtil.parseString(body, "mobile");
		String captchaType = JacksonUtil.parseString(body, "type");
		if (StringUtils.isEmpty(phoneNumber)) {
			return ResponseUtil.badArgument();
		}
		if (!RegexUtil.isMobileExact(phoneNumber)) {
			return ResponseUtil.badArgumentValue();
		}
		if (StringUtils.isEmpty(captchaType)) {
			return ResponseUtil.badArgument();
		}
		if (!notifyService.isSmsEnable()) {
			return ResponseUtil.fail(WxResponseCode.AUTH_CAPTCHA_UNSUPPORT, "小程序后台验证码服务不支持");
		}
		String code = CharUtil.getRandomNum(6);
		notifyService.notifySmsTemplate(phoneNumber, NotifyType.CAPTCHA, new String[] { code });
		boolean successful = CaptchaCodeManager.addToCache(phoneNumber, code);
		if (!successful) {
			return ResponseUtil.fail(WxResponseCode.AUTH_CAPTCHA_FREQUENCY, "验证码未超时1分钟，不能发送");
		}
		return ResponseUtil.ok();
	}

	@PostMapping("reset")
	public Object reset(@RequestBody String body, HttpServletRequest request) {
		String password = JacksonUtil.parseString(body, "password");
		String mobile = JacksonUtil.parseString(body, "mobile");
		String code = JacksonUtil.parseString(body, "code");
		if (mobile == null || code == null || password == null) {
			return ResponseUtil.badArgument();
		}
		String cacheCode = CaptchaCodeManager.getCachedCaptcha(mobile);
		if (cacheCode == null || cacheCode.isEmpty() || !cacheCode.equals(code)) {
			return ResponseUtil.fail(WxResponseCode.AUTH_CAPTCHA_UNMATCH, "验证码错误");
		}
		List<LitemallUser> userList = userService.queryByMobile(mobile);
		LitemallUser user = null;
		if (userList.size() > 1) {
			return ResponseUtil.serious();
		} else if (userList.size() == 0) {
			return ResponseUtil.fail(WxResponseCode.AUTH_MOBILE_UNREGISTERED, "手机号未注册");
		} else {
			user = userList.get(0);
		}
		BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
		String encodedPassword = encoder.encode(password);
		user.setPassword(encodedPassword);
		if (userService.updateById(user) == 0) {
			return ResponseUtil.updatedDataFailed();
		}
		return ResponseUtil.ok();
	}

	@PostMapping("resetPhone")
	public Object resetPhone(@LoginUser Integer userId, @RequestBody String body, HttpServletRequest request) {
		if (userId == null) {
			return ResponseUtil.unlogin();
		}
		String password = JacksonUtil.parseString(body, "password");
		String mobile = JacksonUtil.parseString(body, "mobile");
		String code = JacksonUtil.parseString(body, "code");
		if (mobile == null || code == null || password == null) {
			return ResponseUtil.badArgument();
		}
		String cacheCode = CaptchaCodeManager.getCachedCaptcha(mobile);
		if (cacheCode == null || cacheCode.isEmpty() || !cacheCode.equals(code)) {
			return ResponseUtil.fail(WxResponseCode.AUTH_CAPTCHA_UNMATCH, "验证码错误");
		}
		List<LitemallUser> userList = userService.queryByMobile(mobile);
		LitemallUser user = null;
		if (userList.size() > 1) {
			return ResponseUtil.fail(WxResponseCode.AUTH_MOBILE_REGISTERED, "手机号已注册");
		}
		user = userService.findById(userId);
		BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
		if (!encoder.matches(password, user.getPassword())) {
			return ResponseUtil.fail(WxResponseCode.AUTH_INVALID_ACCOUNT, "账号密码不对");
		}
		user.setMobile(mobile);
		if (userService.updateById(user) == 0) {
			return ResponseUtil.updatedDataFailed();
		}
		return ResponseUtil.ok();
	}

	@PostMapping("profile")
	public Object profile(@LoginUser Integer userId, @RequestBody String body, HttpServletRequest request) {
		if (userId == null) {
			return ResponseUtil.unlogin();
		}
		String avatar = JacksonUtil.parseString(body, "avatar");
		Byte gender = JacksonUtil.parseByte(body, "gender");
		String nickname = JacksonUtil.parseString(body, "nickname");
		LitemallUser user = userService.findById(userId);
		if (!StringUtils.isEmpty(avatar)) {
			user.setAvatar(avatar);
		}
		if (gender != null) {
			user.setGender(gender);
		}
		if (!StringUtils.isEmpty(nickname)) {
			user.setNickname(nickname);
		}
		if (userService.updateById(user) == 0) {
			return ResponseUtil.updatedDataFailed();
		}
		return ResponseUtil.ok();
	}

	@PostMapping("bindPhone")
	public Object bindPhone(@LoginUser Integer userId, @RequestBody String body) {
		if (userId == null) {
			return ResponseUtil.unlogin();
		}
		LitemallUser user = userService.findById(userId);
		String encryptedData = JacksonUtil.parseString(body, "encryptedData");
		String iv = JacksonUtil.parseString(body, "iv");
		WxMaPhoneNumberInfo pni = wxService.getUserService().getPhoneNoInfo(user.getSessionKey(), encryptedData, iv);
		String phone = pni.getPhoneNumber();
		user.setMobile(phone);
		if (userService.updateById(user) == 0) {
			return ResponseUtil.updatedDataFailed();
		}
		return ResponseUtil.ok();
	}

	@PostMapping("logout")
	public Object logout(@LoginUser Integer userId) {
		if (userId == null) {
			return ResponseUtil.unlogin();
		}
		return ResponseUtil.ok();
	}

	@GetMapping("info")
	public Object info(@LoginUser Integer userId) {
		if (userId == null) {
			return ResponseUtil.unlogin();
		}
		LitemallUser user = userService.findById(userId);
		Map<Object, Object> data = new HashMap<Object, Object>();
		data.put("nickName", user.getNickname());
		data.put("avatar", user.getAvatar());
		data.put("gender", user.getGender());
		data.put("mobile", user.getMobile());
		return ResponseUtil.ok(data);
	}
}