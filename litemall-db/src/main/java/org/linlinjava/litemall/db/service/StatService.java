
package org.linlinjava.litemall.db.service;

import org.linlinjava.litemall.db.dao.StatMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

@Service
public class StatService {

	@Resource
	private StatMapper statMapper;

	@SuppressWarnings("rawtypes")
	public List<Map> statUser() {
		return statMapper.statUser();
	}

	@SuppressWarnings("rawtypes")
	public List<Map> statOrder() {
		return statMapper.statOrder();
	}

	@SuppressWarnings("rawtypes")
	public List<Map> statGoods() {
		return statMapper.statGoods();
	}
}